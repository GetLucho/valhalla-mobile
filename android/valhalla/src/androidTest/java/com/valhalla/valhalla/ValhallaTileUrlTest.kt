package com.valhalla.valhalla

import android.content.Context
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import com.valhalla.valhalla.http.ValhallaHttpClient
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread
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

/** Serves the fixture tiles from the test assets over loopback HTTP. */
class LocalTileServer(private val assets: AssetManager) : Closeable {

  /** How the server answers a tile request. */
  enum class Mode {
    /** Gzip with `Content-Encoding: gzip` when the client accepts it, as a real server does. */
    NEGOTIATE,
    /** Never compress. */
    IDENTITY,
    /** Send the tile gzipped with no `Content-Encoding`, like a host serving `.gz` files. */
    PRE_GZIPPED,
    /** Answer 200 with an empty body. */
    EMPTY,
  }

  /** One request: the path, and its `Accept-Encoding` header, if any. */
  data class Request(val path: String, val acceptEncoding: String?)

  @Volatile var mode = Mode.NEGOTIATE

  val requests = CopyOnWriteArrayList<Request>()

  private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

  val port: Int
    get() = socket.localPort

  init {
    thread(isDaemon = true) {
      while (!socket.isClosed) {
        val client =
            try {
              socket.accept()
            } catch (e: IOException) {
              break
            }
        thread(isDaemon = true) { client.use { respond(it) } }
      }
    }
  }

  override fun close() {
    socket.close()
  }

  fun fixture(path: String): ByteArray = assets.open("valhalla_tiles/$path").use { it.readBytes() }

  private fun respond(client: Socket) {
    val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
    val requestLine = reader.readLine() ?: return
    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
    val acceptEncoding =
        headers
            .firstOrNull { it.startsWith("accept-encoding:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    // "GET /2/000/762/485.gph HTTP/1.1"
    val path = requestLine.split(" ").getOrElse(1) { "/" }.trimStart('/')
    requests += Request(path, acceptEncoding)

    val tile =
        try {
          fixture(path)
        } catch (e: IOException) {
          null
        }
    val out = client.getOutputStream()
    if (tile == null) {
      out.write(
          "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
      return
    }

    val compress = mode == Mode.NEGOTIATE && acceptEncoding?.contains("gzip") == true
    val body =
        when (mode) {
          Mode.NEGOTIATE -> if (compress) gzip(tile) else tile
          Mode.IDENTITY -> tile
          Mode.PRE_GZIPPED -> gzip(tile)
          Mode.EMPTY -> ByteArray(0)
        }
    val encoding = if (compress) "Content-Encoding: gzip\r\n" else ""
    out.write(
        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n${encoding}Connection: close\r\n\r\n"
            .toByteArray())
    out.write(body)
    out.flush()
  }

  companion object {
    fun gzip(bytes: ByteArray): ByteArray {
      val out = ByteArrayOutputStream()
      GZIPOutputStream(out).use { it.write(bytes) }
      return out.toByteArray()
    }

    /** Inflates one gzip layer, checking its CRC and length. */
    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
  }
}

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

  @After
  fun tearDown() {
    server.close()
    tilesDir.deleteRecursively()
  }

  private val tileUrl
    get() = "http://127.0.0.1:${server.port}/{tilePath}"

  /** Routes with a new engine, which is what a fresh app launch does. */
  private fun route(tilesAreGzFiles: Boolean): String {
    val config = ValhallaConfigFactory.usingTileUrl(tileUrl, tilesDir.absolutePath, tilesAreGzFiles)
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

  @Test
  fun storesTilesGzipped() {
    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertTrue(server.requests.all { it.acceptEncoding == "gzip" })
    assertStoredGzipped()
  }

  /**
   * Valhalla 3.6.3 checked each tile's checksum before inflating it, so a later launch that had to
   * fetch a tile failed with error 446.
   */
  @Test
  fun laterLaunchFetchesMissingTiles() {
    route(tilesAreGzFiles = true)
    val first = storedTiles().keys
    first.forEach { File(tilesDir, it).delete() }

    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertEquals(first, storedTiles().keys)
    assertStoredGzipped()
  }

  @Test
  fun compressesTilesTheServerSentPlain() {
    server.mode = LocalTileServer.Mode.IDENTITY

    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertStoredGzipped()
  }

  @Test
  fun keepsPreGzippedTilesAsTheyAre() {
    server.mode = LocalTileServer.Mode.PRE_GZIPPED

    assertEquals("Found route between points", route(tilesAreGzFiles = true))

    assertStoredGzipped()
  }

  @Test
  fun rejectsPreGzippedTilesWithTheFlagOff() {
    server.mode = LocalTileServer.Mode.PRE_GZIPPED

    assertThrows(ValhallaException::class.java) { route(tilesAreGzFiles = false) }

    assertTrue("a gzip body was stored as a raw tile", storedTiles().isEmpty())
  }

  /** An empty body used to be stored as a tile that failed on every later load. */
  @Test
  fun doesNotCacheAnEmptyBody() {
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
  fun storesTilesAsServedWithoutTileUrlGz() {
    assertEquals("Found route between points", route(tilesAreGzFiles = false))

    // Left to the platform, which asks for gzip and inflates it.
    assertTrue(server.requests.all { it.acceptEncoding?.contains("gzip") == true })
    val tiles = storedTiles()
    assertFalse("valhalla stored no tiles", tiles.isEmpty())
    for ((path, stored) in tiles) {
      assertTrue("$path was stored compressed", path.endsWith(".gph"))
      assertArrayEquals("$path does not match the server", server.fixture(path), stored)
    }
  }

  private val fixtureUrl
    get() = tileUrl.replace("{tilePath}", "2/000/762/485.gph")

  /** HttpURLConnection only inflates when it chose Accept-Encoding itself. */
  @Test
  fun acceptGzipKeepsTheBodyCompressed() {
    val response = ValhallaHttpClient().get(fixtureUrl, 0, 0, acceptGzip = true)

    assertTrue(response.success)
    assertEquals("gzip", server.requests.single().acceptEncoding)
    val body = requireNotNull(response.body)
    assertArrayEquals(server.fixture("2/000/762/485.gph"), LocalTileServer.gunzip(body))
  }

  @Test
  fun withoutAcceptGzipThePlatformInflates() {
    val response = ValhallaHttpClient().get(fixtureUrl, 0, 0, acceptGzip = false)

    assertTrue(response.success)
    assertTrue(server.requests.single().acceptEncoding?.contains("gzip") == true)
    assertArrayEquals(server.fixture("2/000/762/485.gph"), response.body)
  }

  /** Otherwise the platform would ask for gzip on a slice of a tar. */
  @Test
  fun rangeRequestsAskForIdentity() {
    ValhallaHttpClient().get(fixtureUrl, 0, 512, acceptGzip = false)

    assertEquals("identity", server.requests.single().acceptEncoding)
  }
}
