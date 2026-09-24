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
    }

    private let listener: NWListener
    private let root: URL
    private let queue = DispatchQueue(label: "tile-server", attributes: .concurrent)
    private let lock = NSLock()
    private var paths: [String] = []
    private var compressedResponses = 0
    private var currentMode = Mode.negotiate

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

    init(root: URL) throws {
        self.root = root
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
        let acceptsGzip = lines.contains {
            $0.lowercased().hasPrefix("accept-encoding:") && $0.lowercased().contains("gzip")
        }
        let mode = self.mode

        lock.lock(); paths.append(path); lock.unlock()

        // Serve only files under the fixture root.
        let traversal = path.split(separator: "/").contains("..")
        let file = root.appendingPathComponent(path)
        let stored = traversal ? nil
            : (FileManager.default.fileExists(atPath: file.path) ? try? Data(contentsOf: file) : nil)

        var response = Data()
        if let stored {
            let compress = mode == .negotiate && acceptsGzip
            let body: Data
            switch mode {
            case .negotiate: body = compress ? Self.gzip(stored) : stored
            case .identity: body = stored
            case .preGzipped: body = Self.gzip(stored)
            case .empty: body = Data()
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

    func testRejectsPreGzippedTilesWithTheFlagOff() throws {
        server.mode = .preGzipped

        XCTAssertThrowsError(try route(tilesAreGzFiles: false))
        XCTAssertTrue(try storedTiles().isEmpty, "a gzip body was stored as a raw tile")
    }

    /// An empty body used to be stored as a tile that failed on every later load.
    func testDoesNotCacheAnEmptyBody() throws {
        for gzipped in [true, false] {
            server.mode = .empty
            XCTAssertThrowsError(try route(tilesAreGzFiles: gzipped))
            XCTAssertTrue(try storedTiles().isEmpty, "an empty body was stored (gzip \(gzipped))")

            server.mode = .negotiate
            let response = try route(tilesAreGzFiles: gzipped)
            XCTAssertEqual(response.trip.statusMessage, "Found route between points")

            try FileManager.default.removeItem(at: tilesDir)
            try FileManager.default.createDirectory(at: tilesDir, withIntermediateDirectories: true)
        }
    }

    func testStoresTilesAsServedWithoutTileUrlGz() throws {
        let response = try route(tilesAreGzFiles: false)

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        let tiles = try storedTiles()
        XCTAssertFalse(tiles.isEmpty, "valhalla stored no tiles")
        for (path, stored) in tiles {
            XCTAssertTrue(path.hasSuffix(".gph"), "\(path) was stored compressed")
            XCTAssertEqual(stored, try servedTile(for: path), "\(path) does not match the server")
        }
    }
}
