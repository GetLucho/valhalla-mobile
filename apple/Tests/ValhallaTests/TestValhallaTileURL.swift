import Compression
import Foundation
import Network
import XCTest
import ValhallaModels
import ValhallaConfigModels
@testable import Valhalla

/// Serves the bundled test tiles over HTTP on loopback, compressing when the client offers to
/// take them that way.
///
/// A real socket rather than a `URLProtocol` stub on purpose: the behaviour under test is what
/// NSURLSession does to a response on its way in, and a stub bypasses exactly that.
final class LocalTileServer {

    private let listener: NWListener
    private let root: URL
    private let queue = DispatchQueue(label: "tile-server", attributes: .concurrent)
    private let lock = NSLock()
    private var paths: [String] = []
    private var compressedResponses = 0

    private(set) var port: UInt16 = 0

    /// Every path asked for, so a test can tell "served nothing" from "served a 404".
    var requestedPaths: [String] {
        lock.lock(); defer { lock.unlock() }
        return paths
    }

    /// How many responses went out gzip-compressed.
    var compressedCount: Int {
        lock.lock(); defer { lock.unlock() }
        return compressedResponses
    }

    init(root: URL) throws {
        self.root = root
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        // Loopback only. `.any` binds 0.0.0.0, which trips the macOS incoming-connections
        // prompt — fatal to an unattended test run — and puts the fixture tree on the LAN.
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
                // Without this a bind failure is a ten-second timeout and the real reason is
                // thrown away.
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

    /// HTTP requests arrive in as many segments as the stack feels like, so read until the
    /// header terminator rather than assuming one.
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

        lock.lock(); paths.append(path); lock.unlock()

        // The fixture tree only, whatever the request line says.
        let traversal = path.split(separator: "/").contains("..")
        let file = root.appendingPathComponent(path)
        let stored = traversal ? nil
            : (FileManager.default.fileExists(atPath: file.path) ? try? Data(contentsOf: file) : nil)

        var response = Data()
        if let stored {
            // Compress when offered, the way a real tile server does. This is the case that
            // matters: NSURLSession always offers gzip, and always inflates what comes back.
            let body = acceptsGzip ? Self.gzip(stored) : stored
            if acceptsGzip { lock.lock(); compressedResponses += 1; lock.unlock() }

            var header = "HTTP/1.1 200 OK\r\nContent-Length: \(body.count)\r\n"
            if acceptsGzip { header += "Content-Encoding: gzip\r\n" }
            header += "Connection: close\r\n\r\n"
            response.append(Data(header.utf8))
            response.append(body)
        } else {
            response.append(Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8))
        }

        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }

    // MARK: - gzip

    /// A gzip member around a raw DEFLATE stream: 10-byte header, the deflate bytes, CRC32 and
    /// length little-endian. Compression's COMPRESSION_ZLIB is raw DEFLATE, so the container
    /// has to be written by hand.
    static func gzip(_ data: Data) -> Data {
        var out = Data([0x1f, 0x8b, 0x08, 0x00, 0, 0, 0, 0, 0x00, 0xff])
        out.append(deflate(data))
        var checksum = crc32(data).littleEndian
        withUnsafeBytes(of: &checksum) { out.append(contentsOf: $0) }
        var size = UInt32(truncatingIfNeeded: data.count).littleEndian
        withUnsafeBytes(of: &size) { out.append(contentsOf: $0) }
        return out
    }

    private static func deflate(_ data: Data) -> Data {
        guard !data.isEmpty else { return Data([0x03, 0x00]) }
        let capacity = data.count + 64 * 1024
        var destination = Data(count: capacity)
        let written = destination.withUnsafeMutableBytes { raw -> Int in
            data.withUnsafeBytes { source -> Int in
                compression_encode_buffer(
                    raw.bindMemory(to: UInt8.self).baseAddress!, capacity,
                    source.bindMemory(to: UInt8.self).baseAddress!, data.count,
                    nil, COMPRESSION_ZLIB)
            }
        }
        return destination.prefix(written)
    }

    private static func crc32(_ data: Data) -> UInt32 {
        var table = [UInt32](repeating: 0, count: 256)
        for index in 0..<256 {
            var value = UInt32(index)
            for _ in 0..<8 {
                value = (value & 1) != 0 ? (0xedb8_8320 ^ (value >> 1)) : (value >> 1)
            }
            table[index] = value
        }
        var crc: UInt32 = 0xffff_ffff
        for byte in data {
            crc = table[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8)
        }
        return crc ^ 0xffff_ffff
    }
}

/// `mjolnir.tile_url_gz` against a real tile server.
///
/// NSURLSession inflates every gzip response and offers no way to opt out, so on iOS valhalla
/// can never be handed a gzip stream. Reporting `tile_url_gz` straight through told it
/// otherwise: `DecompressTile` returned null on already-inflated bytes and `CacheTileURL`
/// dereferenced it, killing the process with SIGSEGV. The wrapper now reports what the client
/// can deliver instead, so the flag degrades to false and routing simply works.
final class TestValhallaTileURL: XCTestCase {

    private var server: LocalTileServer!
    private var tilesDir: URL!

    // Not `async`: start() waits on a semaphore, and blocking a cooperative thread for the
    // timeout would starve the concurrency pool.
    override func setUpWithError() throws {
        let fixtures = Bundle.module.resourceURL!
            .appendingPathComponent("TestData/valhalla_tiles", isDirectory: true)
        server = try LocalTileServer(root: fixtures)
        try server.start()

        // Empty, so every tile has to come from the server.
        tilesDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("tile-url-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tilesDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        server?.stop()
        try? FileManager.default.removeItem(at: tilesDir)
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

    private func route(tilesAreGzFiles: Bool, configName: String) throws -> RouteResponse {
        let config = try ValhallaConfig(
            tilesUrl: "http://127.0.0.1:\(server.port)/{tilePath}",
            tilesDir: tilesDir,
            tilesAreGzFiles: tilesAreGzFiles)
        return try Valhalla(config, configName: configName).route(request: andorraRoute)
    }

    /// The regression. Before the wrapper reported the client's capability, this crashed the
    /// process rather than failing, so a failure here may show up as the test run dying.
    func testRoutesOverHttpWithTileUrlGzSet() throws {
        let response = try route(tilesAreGzFiles: true, configName: "tile-url-gz.json")

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        XCTAssertFalse(server.requestedPaths.isEmpty, "no tile was fetched over HTTP")
        // The claim the change rests on: the tiles went over the wire compressed, NSURLSession
        // inflated them on the way in, and routing worked anyway because valhalla was told they
        // were not gzipped. If NSURLSession ever honours an opt-out, this count stays positive
        // but the route fails, which is the signal to revisit delivers_compressed_bytes.
        XCTAssertGreaterThan(server.compressedCount, 0, "NSURLSession did not ask for gzip")
    }

    /// The same route with the flag off, to show the fetching path itself is not what changed.
    func testRoutesOverHttpWithoutTileUrlGz() throws {
        let response = try route(tilesAreGzFiles: false, configName: "tile-url-plain.json")

        XCTAssertEqual(response.trip.statusMessage, "Found route between points")
        XCTAssertFalse(server.requestedPaths.isEmpty, "no tile was fetched over HTTP")
    }
}
