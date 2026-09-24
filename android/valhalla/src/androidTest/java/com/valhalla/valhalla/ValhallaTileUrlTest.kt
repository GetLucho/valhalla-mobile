package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import com.valhalla.valhalla.http.ValhallaHttpClient
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `mjolnir.tile_url_gz` against a real tile server, through Android's own HttpURLConnection. The
 * JVM tests in `src/test` use the JDK's, which behaves differently.
 */
@RunWith(AndroidJUnit4::class)
class ValhallaTileUrlTest {

  private lateinit var context: Context
  private lateinit var server: LocalTileServer
  private lateinit var tilesDir: File

  private val andorraRoute =
      "{\"locations\":[{\"lat\":42.5063,\"lon\":1.5218},{\"lat\":42.5086,\"lon\":1.5394}],\"costing\":\"auto\",\"units\":\"miles\"}"

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    tilesDir = File(context.cacheDir, "tile-url-${UUID.randomUUID()}").apply { mkdirs() }
    server = LocalTileServer(context.assets)
  }

  // Guarded, so a setUp that failed part way reports its own error rather than one from here.
  @After
  fun tearDown() {
    if (::server.isInitialized) server.close()
    if (::tilesDir.isInitialized) tilesDir.deleteRecursively()
  }

  private val tileUrl
    get() = "http://127.0.0.1:${server.port}/{tilePath}"

  /** Routes with a new engine, which is what a fresh app launch does. */
  private fun route(tilesAreGzFiles: Boolean, url: String = tileUrl): String {
    val config = ValhallaConfigFactory.usingTileUrl(url, tilesDir.absolutePath, tilesAreGzFiles)
    val manager = ValhallaConfigManager(context, ValhallaFile(context, "tile-url.json"))
    return Valhalla(context, config, manager).use {
      JSONObject(it.routeRaw(andorraRoute)).getJSONObject("trip").getString("status_message")
    }
  }

  /** Every tile file valhalla stored, keyed by its path relative to [tilesDir]. */
  private fun storedTiles(): Map<String, ByteArray> =
      tilesDir
          .walkTopDown()
          .filter { it.isFile && (it.name.endsWith(".gph") || it.name.endsWith(".gph.gz")) }
          .associate { it.relativeTo(tilesDir).path to it.readBytes() }

  /** Every stored tile is gzipped once and inflates back to the served tile. */
  private fun assertStoredGzipped() {
    val tiles = storedTiles()
    assertFalse("valhalla stored no tiles", tiles.isEmpty())
    for ((path, stored) in tiles) {
      assertTrue("$path was stored uncompressed", path.endsWith(".gph.gz"))
      assertArrayEquals(
          "$path does not match the server",
          server.fixture(path.removeSuffix(".gz")),
          LocalTileServer.gunzip(stored))
    }
  }

  /** Every stored tile is raw and matches the served tile. */
  private fun assertStoredRaw() {
    val tiles = storedTiles()
    assertFalse("valhalla stored no tiles", tiles.isEmpty())
    for ((path, stored) in tiles) {
      assertTrue("$path was stored compressed", path.endsWith(".gph"))
      assertArrayEquals("$path does not match the server", server.fixture(path), stored)
    }
  }

  /** A remote tar, which valhalla reads in byte ranges. */
  private val tarUrl
    get() = "http://127.0.0.1:${server.port}/${LocalTileServer.REMOTE_TAR_PATH}"

  @Test
  fun testStoresTilesGzipped() {
    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertTrue(server.requests.all { it.acceptEncoding == "gzip" })
    assertStoredGzipped()
  }

  /**
   * Valhalla 3.6.3 checked each tile's checksum before inflating it, so a later launch that had to
   * fetch a tile failed with error 446.
   */
  @Test
  fun testLaterLaunchFetchesMissingTiles() {
    route(tilesAreGzFiles = true)
    val first = storedTiles().keys
    first.forEach { File(tilesDir, it).delete() }

    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertEquals(first, storedTiles().keys)
    assertStoredGzipped()
  }

  @Test
  fun testCompressesTilesTheServerSentPlain() {
    server.mode = LocalTileServer.Mode.IDENTITY

    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertStoredGzipped()
  }

  @Test
  fun testKeepsPreGzippedTilesAsTheyAre() {
    server.mode = LocalTileServer.Mode.PRE_GZIPPED

    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertStoredGzipped()
  }

  @Test
  fun testInflatesPreGzippedTilesWithTheFlagOff() {
    server.mode = LocalTileServer.Mode.PRE_GZIPPED

    assertEquals("Found route between points", route(tilesAreGzFiles = false))

    assertStoredRaw()
  }

  /** A gzip body whose header is fine but whose tail is corrupt used to crash valhalla 3.9.0. */
  @Test
  fun testRejectsAGzipBodyWithACorruptTail() {
    server.mode = LocalTileServer.Mode.CORRUPT_GZIP_TAIL
    for (gzipped in listOf(true, false)) {
      assertThrows(ValhallaException::class.java) { route(gzipped) }
      assertTrue("a corrupt gzip body was stored (gzip $gzipped)", storedTiles().isEmpty())
    }
  }

  @Test
  fun testRejectsABodyThatIsNotATile() {
    server.mode = LocalTileServer.Mode.NOT_A_TILE
    for (gzipped in listOf(true, false)) {
      assertThrows(ValhallaException::class.java) { route(gzipped) }
      assertTrue("a page that isn't a tile was stored (gzip $gzipped)", storedTiles().isEmpty())
    }
  }

  /** An empty body used to be stored as a tile that failed on every later load. */
  @Test
  fun testDoesNotCacheAnEmptyBody() {
    for (gzipped in listOf(true, false)) {
      server.mode = LocalTileServer.Mode.EMPTY
      assertThrows(ValhallaException::class.java) { route(gzipped) }
      assertTrue("an empty body was stored (gzip $gzipped)", storedTiles().isEmpty())

      server.mode = LocalTileServer.Mode.NEGOTIATE
      assertEquals("Found route between points", route(gzipped))

      tilesDir.deleteRecursively()
      tilesDir.mkdirs()
    }
  }

  @Test
  fun testStoresTilesAsServedWithoutTileUrlGz() {
    assertEquals("Found route between points", route(tilesAreGzFiles = false))

    // Left to the platform, which asks for gzip and inflates it.
    assertTrue(server.requests.all { it.acceptEncoding?.contains("gzip") == true })
    assertStoredRaw()
  }

  /** A remote tar holds raw tiles, so tile_url_gz doesn't apply to it. */
  @Test
  fun testRemoteTarIgnoresTileUrlGz() {
    assertEquals("Found route between points", route(tilesAreGzFiles = true, url = tarUrl))

    assertStoredRaw()
    val ranged = server.requests.filter { it.ranged }
    assertFalse("valhalla sent no range requests", ranged.isEmpty())
    assertTrue(ranged.all { it.acceptEncoding == "identity" })
  }

  @Test
  fun testRejectsARangeTheServerIgnored() {
    server.mode = LocalTileServer.Mode.IGNORE_RANGE

    assertThrows(ValhallaException::class.java) { route(tilesAreGzFiles = false, url = tarUrl) }

    assertTrue("the whole tar was stored as a tile", storedTiles().isEmpty())
  }

  private val fixtureUrl
    get() = tileUrl.replace("{tilePath}", "2/000/762/485.gph")

  /** HttpURLConnection only inflates when it chose Accept-Encoding itself. */
  @Test
  fun testAcceptGzipKeepsTheBodyCompressed() {
    val response = ValhallaHttpClient().get(fixtureUrl, 0, 0, acceptGzip = true)

    assertTrue(response.success)
    assertEquals("gzip", server.requests.single().acceptEncoding)
    val body = requireNotNull(response.body)
    assertArrayEquals(server.fixture("2/000/762/485.gph"), LocalTileServer.gunzip(body))
  }

  @Test
  fun testWithoutAcceptGzipThePlatformInflates() {
    val response = ValhallaHttpClient().get(fixtureUrl, 0, 0, acceptGzip = false)

    assertTrue(response.success)
    assertTrue(server.requests.single().acceptEncoding?.contains("gzip") == true)
    assertArrayEquals(server.fixture("2/000/762/485.gph"), response.body)
  }

  /** Otherwise the platform would ask for gzip on a slice of a tar. */
  @Test
  fun testRangeRequestsAskForIdentity() {
    ValhallaHttpClient().get(fixtureUrl, 0, 512, acceptGzip = false)

    assertEquals("identity", server.requests.single().acceptEncoding)
  }
}
