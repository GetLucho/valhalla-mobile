import Foundation
import Network
import XCTest
import zlib
import ValhallaModels
import ValhallaConfigModels
@testable import Valhalla

/// Serves the bundled test tiles over loopback HTTP.
///
/// A real socket rather than a `URLProtocol` stub, because the behavior under test is how
/// NSURLSession handles the response, which a stub would bypass.
final class LocalTileServer {

    /// How the server answers a tile request.
    enum Mode {
        /// Gzip with `Content-Encoding: gzip` when the client accepts it, as a real tile server does.
        case negotiate
        /// Never compress.
        case identity
        /// Send the tile gzipped with no `Content-Encoding`, like a host serving `.gz` files.
        case preGzipped
        /// Answer 200 with an empty body.
        case empty
        /// Send the tile gzipped with a corrupt byte near the end, and no `Content-Encoding`.
        case corruptGzipTail
        /// Answer 200 with a page that isn't a tile, like a captive portal.
        case notATile
        /// Answer a Range request with 200 and the whole file.
        case ignoreRange
    }

    /// The path of a remote tar built from the fixture tiles.
    static let remoteTarPath = "remote.tar"

    private let listener: NWListener
    private let root: URL
    private let queue = DispatchQueue(label: "tile-server", attributes: .concurrent)
    private let lock = NSLock()
    private var paths: [String] = []
    private var compressedResponses = 0
    private var rangeAcceptEncodings: [String] = []
    private var currentMode = Mode.negotiate
    private let remoteTar: Data

    private(set) var port: UInt16 = 0

    var mode: Mode {
        get { lock.lock(); defer { lock.unlock() }; return currentMode }
        set { lock.lock(); currentMode = newValue; lock.unlock() }
    }

    /// Every path requested.
    var requestedPaths: [String] {
        lock.lock(); defer { lock.unlock() }
        return paths
    }

    /// How many responses went out with `Content-Encoding: gzip`.
    var compressedCount: Int {
        lock.lock(); defer { lock.unlock() }
        return compressedResponses
    }

    /// The `Accept-Encoding` of every Range request, empty when none was sent.
    var rangeEncodings: [String] {
        lock.lock(); defer { lock.unlock() }
        return rangeAcceptEncodings
    }

    init(root: URL) throws {
        self.root = root
        remoteTar = try Self.remoteTar(root: root)
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        // Loopback only: binding 0.0.0.0 triggers the macOS incoming-connections prompt.
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }

    func start() throws {
        let ready = DispatchSemaphore(value: 0)
        var failure: Error?

        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                ready.signal()
            case .failed(let error), .waiting(let error):
                // Report bind failures instead of timing out.
                failure = error
                ready.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] in self?.accept($0) }
        listener.start(queue: queue)

        guard ready.wait(timeout: .now() + 10) == .success else {
            throw NSError(domain: "LocalTileServer", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "listener never became ready"])
        }
        if let failure { throw failure }
        guard let bound = listener.port?.rawValue else {
            throw NSError(domain: "LocalTileServer", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "listener reported no port"])
        }
        port = bound
    }

    func stop() {
        listener.cancel()
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        read(connection, accumulated: Data())
    }

    /// Reads until the end of the request headers, which can arrive in several segments.
    private func read(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) {
            [weak self] chunk, _, isComplete, error in
            guard let self else { return }
            var buffer = accumulated
            if let chunk { buffer.append(chunk) }

            guard let terminator = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                if error != nil || isComplete {
                    connection.cancel()
                } else {
                    self.read(connection, accumulated: buffer)
                }
                return
            }

            let head = String(decoding: buffer[..<terminator.lowerBound], as: UTF8.self)
            self.respond(to: head, on: connection)
        }
    }

    private func respond(to head: String, on connection: NWConnection) {
        let lines = head.split(separator: "\r\n")
        // "GET /2/000/762/485.gph HTTP/1.1"
        let target = lines.first?.split(separator: " ").dropFirst().first
        let path = String(target ?? "/").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        func headerValue(_ name: String) -> String? {
            lines.first { $0.lowercased().hasPrefix(name + ":") }
                .map { $0.dropFirst(name.count + 1).trimmingCharacters(in: .whitespaces) }
        }
        let acceptsGzip = headerValue("accept-encoding")?.lowercased().contains("gzip") == true
        let range = headerValue("range").flatMap(Self.parseRange)
        let mode = self.mode

        lock.lock()
        paths.append(path)
        if range != nil { rangeAcceptEncodings.append(headerValue("accept-encoding") ?? "") }
        lock.unlock()

        // Serve only files under the fixture root, and the tar built from them.
        let traversal = path.split(separator: "/").contains("..")
        let file = root.appendingPathComponent(path)
        let stored = path == Self.remoteTarPath ? remoteTar
            : traversal ? nil
            : (FileManager.default.fileExists(atPath: file.path) ? try? Data(contentsOf: file) : nil)

        var response = Data()
        if let stored, let range, mode != .ignoreRange {
            let slice = stored.subdata(in: range.clamped(to: 0..<stored.count))
            let last = range.lowerBound + slice.count - 1
            response.append(Data(("HTTP/1.1 206 Partial Content\r\nContent-Length: \(slice.count)\r\n"
                + "Content-Range: bytes \(range.lowerBound)-\(last)/\(stored.count)\r\n"
                + "Connection: close\r\n\r\n").utf8))
            response.append(slice)
        } else if let stored {
            let compress = mode == .negotiate && acceptsGzip
            let body: Data
            switch mode {
            case .negotiate: body = compress ? Self.gzip(stored) : stored
            case .identity, .ignoreRange: body = stored
            case .preGzipped: body = Self.gzip(stored)
            case .empty: body = Data()
            case .corruptGzipTail:
                var gzip = Self.gzip(stored)
                gzip[gzip.count - 20] ^= 0xff
                body = gzip
            case .notATile:
                body = Data(("<html><body>" + String(repeating: "Sign in to continue. ", count: 100)
                    + "</body></html>").utf8)
            }
            if compress { lock.lock(); compressedResponses += 1; lock.unlock() }

            var header = "HTTP/1.1 200 OK\r\nContent-Length: \(body.count)\r\n"
            if compress { header += "Content-Encoding: gzip\r\n" }
            header += "Connection: close\r\n\r\n"
            response.append(Data(header.utf8))
            response.append(body)
        } else {
            response.append(Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8))
        }

        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }

    /// "bytes=0-511", inclusive on both ends.
    private static func parseRange(_ value: String) -> Range<Int>? {
        guard value.hasPrefix("bytes=") else { return nil }
        let bounds = value.dropFirst(6).split(separator: "-").compactMap { Int($0) }
        return bounds.count == 2 && bounds[0] <= bounds[1] ? bounds[0]..<(bounds[1] + 1) : nil
    }

    // MARK: - Remote tar

    /// A tar of the fixture tiles, laid out the way valhalla reads one remotely:
    /// index.bin first, giving each tile's offset, id, and size.
    static func remoteTar(root: URL) throws -> Data {
        let base = root.resolvingSymlinksInPath().path + "/"
        let paths = (FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?
            .compactMap { ($0 as? URL)?.resolvingSymlinksInPath().path } ?? [])
            .filter { $0.hasSuffix(".gph") }
            .map { String($0.dropFirst(base.count)) }
            .sorted()
        let indexSize = paths.count * 16
        var offset = 512 + padded(indexSize)
        var index = Data()
        var body = Data()
        for path in paths {
            let tile = try Data(contentsOf: root.appendingPathComponent(path))
            append(UInt64(offset + 512), to: &index)
            append(tileId(path), to: &index)
            append(UInt32(tile.count), to: &index)
            body.append(tarHeader(name: path, size: tile.count))
            body.append(tile)
            body.append(Data(count: padded(tile.count) - tile.count))
            offset += 512 + padded(tile.count)
        }
        var tar = tarHeader(name: "index.bin", size: indexSize)
        tar.append(index)
        tar.append(Data(count: padded(indexSize) - indexSize))
        tar.append(body)
        tar.append(Data(count: 1024))
        return tar
    }

    private static func padded(_ size: Int) -> Int { (size + 511) / 512 * 512 }

    private static func append<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }

    /// "2/000/762/485.gph" is level 2, tile 762485.
    private static func tileId(_ path: String) -> UInt32 {
        let parts = path.dropLast(4).split(separator: "/")
        return UInt32(parts[0])! | UInt32(parts.dropFirst().joined())! << 3
    }

    private static func tarHeader(name: String, size: Int) -> Data {
        var header = [UInt8](repeating: 0, count: 512)
        func put(_ text: String, at offset: Int) {
            for (i, byte) in text.utf8.enumerated() { header[offset + i] = byte }
        }
        put(name, at: 0)
        put("0000644", at: 100)
        put("0000000", at: 108)
        put("0000000", at: 116)
        put(String(format: "%011o", size), at: 124)
        put("00000000000", at: 136)
        put("0", at: 156)
        put("ustar", at: 257)
        put("00", at: 263)
        put("        ", at: 148)
        let sum = header.reduce(0) { $0 + Int($1) }
        put(String(format: "%06o", sum), at: 148)
        header[154] = 0
        return Data(header)
    }

    // MARK: - gzip

    static func gzip(_ data: Data) -> Data {
        var stream = z_stream()
        precondition(deflateInit2_(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, MAX_WBITS + 16, 8,
                                   Z_DEFAULT_STRATEGY, ZLIB_VERSION,
                                   Int32(MemoryLayout<z_stream>.size)) == Z_OK)
        defer { deflateEnd(&stream) }
        var out = Data(count: Int(deflateBound(&stream, uLong(data.count))))
        let written = data.withUnsafeBytes { source in
            out.withUnsafeMutableBytes { destination -> Int in
                stream.next_in = UnsafeMutablePointer(
                    mutating: source.bindMemory(to: Bytef.self).baseAddress)
                stream.avail_in = uInt(source.count)
                stream.next_out = destination.bindMemory(to: Bytef.self).baseAddress
                stream.avail_out = uInt(destination.count)
                precondition(deflate(&stream, Z_FINISH) == Z_STREAM_END)
                return Int(stream.total_out)
            }
        }
        return out.prefix(written)
    }

    /// Inflates exactly one gzip stream, checking its CRC and length, or returns nil.
    static func gunzip(_ data: Data) -> Data? {
        var stream = z_stream()
        guard inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION,
                            Int32(MemoryLayout<z_stream>.size)) == Z_OK else { return nil }
        defer { inflateEnd(&stream) }
        var out = Data()
        var chunk = [UInt8](repeating: 0, count: 64 * 1024)
        let status = data.withUnsafeBytes { source -> Int32 in
            stream.next_in = UnsafeMutablePointer(
                mutating: source.bindMemory(to: Bytef.self).baseAddress)
            stream.avail_in = uInt(source.count)
            var status = Z_OK
            repeat {
                status = chunk.withUnsafeMutableBufferPointer { buffer in
                    stream.next_out = buffer.baseAddress
                    stream.avail_out = uInt(buffer.count)
                    return inflate(&stream, Z_NO_FLUSH)
                }
                out.append(contentsOf: chunk[0..<(chunk.count - Int(stream.avail_out))])
            } while status == Z_OK
            return status
        }
        return status == Z_STREAM_END && stream.avail_in == 0 ? out : nil
    }
}

/// `mjolnir.tile_url_gz` against a real tile server.
///
/// NSURLSession always inflates gzip responses, so with the flag on the wrapper recompresses each
/// tile before valhalla stores it.
final class TestValhallaTileURL: XCTestCase {

    private var server: LocalTileServer!
    private var fixtures: URL!
    private var tilesDir: URL!

    // Not `async`: start() blocks on a semaphore, which shouldn't tie up a cooperative thread.
    override func setUpWithError() throws {
        fixtures = Bundle.module.resourceURL!
            .appendingPathComponent("TestData/valhalla_tiles", isDirectory: true)

        // Before the server starts, so tearDown has a directory to remove even if start() throws.
        tilesDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("tile-url-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tilesDir, withIntermediateDirectories: true)

        server = try LocalTileServer(root: fixtures)
        try server.start()
    }

    override func tearDownWithError() throws {
        server?.stop()
        if let tilesDir {
            try? FileManager.default.removeItem(at: tilesDir)
        }
    }

    private var andorraRoute: RouteRequest {
        RouteRequest(
            locations: [
                RoutingWaypoint(lat: 42.5063, lon: 1.5218),
                RoutingWaypoint(lat: 42.5086, lon: 1.5394)
            ],
            costing: .auto,
            units: .mi
        )
    }

    /// Routes with a new `Valhalla`, which is what a fresh app launch does.
    private func route(tilesAreGzFiles: Bool) throws -> RouteResponse {
        let config = try ValhallaConfig(
            tilesUrl: "http://127.0.0.1:\(server.port)/{tilePath}",
            tilesDir: tilesDir,
            tilesAreGzFiles: tilesAreGzFiles)
        let name = tilesAreGzFiles ? "tile-url-gz.json" : "tile-url-plain.json"
        return try Valhalla(config, configName: name).route(request: andorraRoute)
    }

    /// Routes from a remote tar, which valhalla reads in byte ranges.
    private func routeFromTar(tilesAreGzFiles: Bool) throws -> RouteResponse {
        let config = try ValhallaConfig(
            tilesUrl: "http://127.0.0.1:\(server.port)/\(LocalTileServer.remoteTarPath)",
            tilesDir: tilesDir,
            tilesAreGzFiles: tilesAreGzFiles)
        return try Valhalla(config, configName: "tile-url-tar.json").route(request: andorraRoute)
    }

    /// Every tile file valhalla stored, keyed by its path relative to `tilesDir`.
    private func storedTiles() throws -> [String: Data] {
        let root = tilesDir.resolvingSymlinksInPath().path + "/"
        var tiles: [String: Data] = [:]
        let files = FileManager.default.enumerator(at: tilesDir, includingPropertiesForKeys: nil)
        while let file = files?.nextObject() as? URL {
            let path = file.resolvingSymlinksInPath().path
            guard path.hasSuffix(".gph") || path.hasSuffix(".gph.gz") else { continue }
            tiles[String(path.dropFirst(root.count))] = try Data(contentsOf: file)
        }
        return tiles
    }

    /// The fixture served for a stored path. Valhalla adds `.gz` when it stores gzipped tiles.
    private func servedTile(for storedPath: String) throws -> Data {
        let requested = storedPath.hasSuffix(".gz") ? String(storedPath.dropLast(3)) : storedPath
        return try Data(contentsOf: fixtures.appendingPathComponent(requested))
    }

    /// Every stored tile is gzipped once and inflates back to the served tile.
    private func assertStoredGzipped(file: StaticString = #filePath, line: UInt = #line) throws {
        let tiles = try storedTiles()
        XCTAssertFalse(tiles.isEmpty, "valhalla stored no tiles", file: file, line: line)
        for (path, stored) in tiles {
            XCTAssertTrue(path.hasSuffix(".gph.gz"), "\(path) was stored uncompressed",
                          file: file, line: line)
            let inflated = try XCTUnwrap(LocalTileServer.gunzip(stored),
                                         "\(path) is not one gzip stream", file: file, line: line)
            XCTAssertEqual(inflated, try servedTile(for: path),
                           "\(path) does not match the server", file: file, line: line)
        }
    }

    /// Every stored tile is raw and matches the served tile.
    private func assertStoredRaw(file: StaticString = #filePath, line: UInt = #line) throws {
        let tiles = try storedTiles()
        XCTAssertFalse(tiles.isEmpty, "valhalla stored no tiles", file: file, line: line)
        for (path, stored) in tiles {
            XCTAssertTrue(path.hasSuffix(".gph"), "\(path) was stored compressed", file: file, line: line)
            XCTAssertEqual(stored, try servedTile(for: path), "\(path) does not match the server",
                           file: file, line: line)
        }
    }

    /// Nothing was cached, so a later launch fetches again instead of loading a broken tile.
    private func assertNothingStored(_ why: String, file: StaticString = #filePath, line: UInt = #line) throws {
        XCTAssertTrue(try storedTiles().isEmpty, why, file: file, line: line)
    }

    private func resetTilesDir() throws {
        try FileManager.default.removeItem(at: tilesDir)
        try FileManager.default.createDirectory(at: tilesDir, withIntermediateDirectories: true)
    }

    func testStoresTilesGzipped() throws {
        let response = try route(tilesAreGzFiles: true)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        // The server sent gzip, so NSURLSession inflated it and the wrapper had to recompress.
        XCTAssertGreaterThan(server.compressedCount, 0, "NSURLSession did not ask for gzip")
        try assertStoredGzipped()
    }

    /// Valhalla 3.6.3 checked each tile's checksum before inflating it, so a later launch that had
    /// to fetch a tile failed with error 446.
    func testLaterLaunchFetchesMissingTiles() throws {
        _ = try route(tilesAreGzFiles: true)
        let first = try storedTiles()
        for path in first.keys {
            try FileManager.default.removeItem(at: tilesDir.appendingPathComponent(path))
        }

        let response = try route(tilesAreGzFiles: true)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        XCTAssertEqual(Set(try storedTiles().keys), Set(first.keys))
        try assertStoredGzipped()
    }

    func testCompressesTilesTheServerSentPlain() throws {
        server.mode = .identity

        let response = try route(tilesAreGzFiles: true)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        try assertStoredGzipped()
    }

    /// NSURLSession leaves a gzip body alone when there's no `Content-Encoding`, so it must not be
    /// compressed a second time.
    func testKeepsPreGzippedTilesAsTheyAre() throws {
        server.mode = .preGzipped

        let response = try route(tilesAreGzFiles: true)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        try assertStoredGzipped()
    }

    func testInflatesPreGzippedTilesWithTheFlagOff() throws {
        server.mode = .preGzipped

        let response = try route(tilesAreGzFiles: false)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        try assertStoredRaw()
    }

    /// A gzip body whose header is fine but whose tail is corrupt used to crash valhalla 3.9.0.
    func testRejectsAGzipBodyWithACorruptTail() throws {
        server.mode = .corruptGzipTail
        for gzipped in [true, false] {
            XCTAssertThrowsError(try route(tilesAreGzFiles: gzipped))
            try assertNothingStored("a corrupt gzip body was stored (gzip \(gzipped))")
        }
    }

    func testRejectsABodyThatIsNotATile() throws {
        server.mode = .notATile
        for gzipped in [true, false] {
            XCTAssertThrowsError(try route(tilesAreGzFiles: gzipped))
            try assertNothingStored("a page that isn't a tile was stored (gzip \(gzipped))")
        }
    }

    /// An empty body used to be stored as a tile that failed on every later load.
    func testDoesNotCacheAnEmptyBody() throws {
        for gzipped in [true, false] {
            server.mode = .empty
            XCTAssertThrowsError(try route(tilesAreGzFiles: gzipped))
            try assertNothingStored("an empty body was stored (gzip \(gzipped))")

            server.mode = .negotiate
            let response = try route(tilesAreGzFiles: gzipped)
            XCTAssertEqual(response.trip.statusMessage, "Found route between points")

            try resetTilesDir()
        }
    }

    func testStoresTilesAsServedWithoutTileUrlGz() throws {
        let response = try route(tilesAreGzFiles: false)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        try assertStoredRaw()
    }

    /// A remote tar holds raw tiles, so tile_url_gz doesn't apply to it.
    func testRemoteTarIgnoresTileUrlGz() throws {
        let response = try routeFromTar(tilesAreGzFiles: true)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        try assertStoredRaw()
        // Otherwise a server can answer a slice of the tar with the whole tar compressed.
        XCTAssertFalse(server.rangeEncodings.isEmpty, "valhalla sent no range requests")
        XCTAssertEqual(Set(server.rangeEncodings), ["identity"])
    }

    func testRejectsARangeTheServerIgnored() throws {
        server.mode = .ignoreRange

        XCTAssertThrowsError(try routeFromTar(tilesAreGzFiles: false))
        try assertNothingStored("the whole tar was stored as a tile")
    }
}
