import Foundation
import Network
import XCTest
import zlib
import ValhallaModels
import ValhallaConfigModels
@testable import Valhalla

/// Serves the test fixtures over loopback HTTP, gzipping whole files when the client accepts it.
///
/// A real socket, because NSURLSession's decompression is what's under test.
final class LocalTileServer {

    private let listener: NWListener
    private let root: URL
    private let queue = DispatchQueue(label: "tile-server", attributes: .concurrent)
    private let lock = NSLock()
    private var compressed = 0
    private var rangeEncodings: [String] = []

    private(set) var port: UInt16 = 0

    /// How many responses went out with `Content-Encoding: gzip`.
    var compressedCount: Int {
        lock.lock(); defer { lock.unlock() }
        return compressed
    }

    /// The `Accept-Encoding` of every Range request.
    var rangeAcceptEncodings: [String] {
        lock.lock(); defer { lock.unlock() }
        return rangeEncodings
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
                failure = error
                ready.signal()
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] in self?.accept($0) }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 10) == .success else {
            throw NSError(domain: "LocalTileServer", code: 1)
        }
        if let failure { throw failure }
        guard let port = listener.port?.rawValue else {
            throw NSError(domain: "LocalTileServer", code: 2)
        }
        self.port = port
    }

    func stop() {
        listener.cancel()
    }

    private func accept(_ connection: NWConnection) {
        connection.start(queue: queue)
        read(connection, accumulated: Data())
    }

    private func read(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) {
            [weak self] chunk, _, isComplete, error in
            guard let self else { return }
            var buffer = accumulated
            if let chunk { buffer.append(chunk) }
            guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                if error != nil || isComplete {
                    connection.cancel()
                } else {
                    self.read(connection, accumulated: buffer)
                }
                return
            }
            self.respond(to: String(decoding: buffer[..<end.lowerBound], as: UTF8.self), on: connection)
        }
    }

    private func respond(to head: String, on connection: NWConnection) {
        let lines = head.split(separator: "\r\n")
        func header(_ name: String) -> String? {
            lines.first { $0.lowercased().hasPrefix(name + ":") }
                .map { $0.dropFirst(name.count + 1).trimmingCharacters(in: .whitespaces) }
        }
        // "GET /valhalla_tiles/2/000/762/485.gph HTTP/1.1"
        let path = String(lines.first?.split(separator: " ").dropFirst().first ?? "/")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let range = header("range").flatMap(Self.parseRange)
        let file = path.split(separator: "/").contains("..") ? nil
            : try? Data(contentsOf: root.appendingPathComponent(path))

        var response = Data()
        if let file, let range {
            lock.lock(); rangeEncodings.append(header("accept-encoding") ?? ""); lock.unlock()
            let slice = file.subdata(in: range.clamped(to: 0..<file.count))
            response.append(Data(("HTTP/1.1 206 Partial Content\r\nContent-Length: \(slice.count)\r\n"
                + "Connection: close\r\n\r\n").utf8))
            response.append(slice)
        } else if let file {
            let compress = header("accept-encoding")?.contains("gzip") == true
            if compress { lock.lock(); compressed += 1; lock.unlock() }
            let body = compress ? Self.gzip(file) : file
            response.append(Data(("HTTP/1.1 200 OK\r\nContent-Length: \(body.count)\r\n"
                + (compress ? "Content-Encoding: gzip\r\n" : "") + "Connection: close\r\n\r\n").utf8))
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
}

/// `mjolnir.tile_url_gz` through NSURLSession. The shared C++ edge cases are covered on Android.
final class TestValhallaTileURL: XCTestCase {

    private var server: LocalTileServer!
    private var fixtures: URL!
    private var tilesDir: URL!

    override func setUpWithError() throws {
        fixtures = Bundle.module.resourceURL!.appendingPathComponent("TestData", isDirectory: true)
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

    private func route(_ path: String, tilesAreGzFiles: Bool) throws -> String? {
        let config = try ValhallaConfig(
            tilesUrl: "http://127.0.0.1:\(server.port)/\(path)",
            tilesDir: tilesDir,
            tilesAreGzFiles: tilesAreGzFiles)
        let request = RouteRequest(
            locations: [
                RoutingWaypoint(lat: 42.5063, lon: 1.5218),
                RoutingWaypoint(lat: 42.5086, lon: 1.5394)
            ],
            costing: .auto,
            units: .mi)
        return try Valhalla(config, configName: "tile-url.json").route(request: request).trip.statusMessage
    }

    /// Every stored tile, keyed by its path under `tilesDir`, with the fixture it came from.
    private func storedTiles() throws -> [(path: String, stored: Data, served: Data)] {
        let root = tilesDir.resolvingSymlinksInPath().path + "/"
        var tiles: [(String, Data, Data)] = []
        let files = FileManager.default.enumerator(at: tilesDir, includingPropertiesForKeys: nil)
        while let file = files?.nextObject() as? URL {
            let path = String(file.resolvingSymlinksInPath().path.dropFirst(root.count))
            guard path.hasSuffix(".gph") || path.hasSuffix(".gph.gz") else { continue }
            let served = fixtures.appendingPathComponent("valhalla_tiles")
                .appendingPathComponent(path.hasSuffix(".gz") ? String(path.dropLast(3)) : path)
            tiles.append((path, try Data(contentsOf: file), try Data(contentsOf: served)))
        }
        XCTAssertFalse(tiles.isEmpty, "nothing was stored")
        return tiles
    }

    func testStoresTilesGzipped() throws {
        XCTAssertEqual(try route("valhalla_tiles/{tilePath}", tilesAreGzFiles: true),
                       "Found route between points")

        XCTAssertGreaterThan(server.compressedCount, 0)
        for tile in try storedTiles() {
            XCTAssertTrue(tile.path.hasSuffix(".gph.gz"), tile.path)
            XCTAssertEqual(tile.stored.prefix(2), Data([0x1f, 0x8b]), tile.path)
        }
    }

    func testStoresTilesRawWithoutTileUrlGz() throws {
        XCTAssertEqual(try route("valhalla_tiles/{tilePath}", tilesAreGzFiles: false),
                       "Found route between points")

        for tile in try storedTiles() {
            XCTAssertTrue(tile.path.hasSuffix(".gph"), tile.path)
            XCTAssertEqual(tile.stored, tile.served, tile.path)
        }
    }

    func testRemoteTarRangesAskForIdentity() throws {
        XCTAssertEqual(try route("valhalla_tiles.tar", tilesAreGzFiles: true),
                       "Found route between points")

        XCTAssertFalse(server.rangeAcceptEncodings.isEmpty)
        XCTAssertEqual(Set(server.rangeAcceptEncodings), ["identity"])
        for tile in try storedTiles() {
            XCTAssertEqual(tile.stored, tile.served, tile.path)
        }
    }
}
