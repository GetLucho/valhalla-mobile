import Foundation
import XCTest
import ValhallaConfigModels
@testable import Valhalla

/// `ensureTileCached` against the fixtures served over loopback.
final class TestValhallaPrefetch: XCTestCase {

    private static let tile: UInt32 = 762_485
    private static let missingTile: UInt32 = 762_484

    private var server: LocalTileServer!
    private var root: URL!

    private var tilesDir: URL { root.appendingPathComponent("tiles", isDirectory: true) }

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("prefetch-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tilesDir, withIntermediateDirectories: true)
        server = try LocalTileServer(
            root: Bundle.module.resourceURL!.appendingPathComponent("TestData", isDirectory: true))
        try server.start()
    }

    override func tearDownWithError() throws {
        server?.stop()
        if let root {
            try? FileManager.default.removeItem(at: root)
        }
    }

    /// A new engine on the fixture server, with `mjolnir.tile_url_timeout` set when given.
    private func valhalla(timeoutSeconds: Double? = nil) throws -> Valhalla {
        let config = try ValhallaConfig(
            tilesUrl: "http://127.0.0.1:\(server.port)/valhalla_tiles/{tilePath}",
            tilesDir: tilesDir,
            tilesAreGzFiles: false)
        var json = try XCTUnwrap(
            JSONSerialization.jsonObject(with: JSONEncoder().encode(config)) as? [String: Any])
        var mjolnir = try XCTUnwrap(json["mjolnir"] as? [String: Any])
        if let timeoutSeconds {
            mjolnir["tile_url_timeout"] = timeoutSeconds
        }
        json["mjolnir"] = mjolnir
        let configURL = root.appendingPathComponent("valhalla.json")
        try JSONSerialization.data(withJSONObject: json).write(to: configURL)
        return try Valhalla(configPath: configURL.path)
    }

    func testFetchesATileTheOriginHas() throws {
        XCTAssertTrue(try valhalla().ensureTileCached(level: 2, id: Self.tile))

        XCTAssertTrue(FileManager.default.fileExists(
            atPath: tilesDir.appendingPathComponent("2/000/762/485.gph").path))
    }

    func testReturnsFalseForATileTheOriginDoesNotHave() throws {
        XCTAssertFalse(try valhalla().ensureTileCached(level: 2, id: Self.missingTile))
    }

    func testThrowsWhenCancelled() throws {
        let valhalla = try valhalla()
        valhalla.cancel()

        XCTAssertThrowsError(try valhalla.ensureTileCached(level: 2, id: Self.tile)) { error in
            XCTAssertEqual(error as? ValhallaError, .valhallaError(-1, "valhalla-mobile: cancelled"))
        }

        valhalla.resume()
        XCTAssertTrue(try valhalla.ensureTileCached(level: 2, id: Self.tile))
    }

    func testThrowsWhenTheDeadlineFires() throws {
        let valhalla = try valhalla(timeoutSeconds: 1e-9)

        XCTAssertThrowsError(try valhalla.ensureTileCached(level: 2, id: Self.tile)) { error in
            XCTAssertEqual(error as? ValhallaError,
                           .valhallaError(-1, "valhalla-mobile: tile fetch deadline"))
        }
    }
}
