package com.valhalla.valhalla

import android.content.Context
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import com.valhalla.valhalla.http.ValhallaHttpClient
import com.valhalla.valhalla.http.ValhallaHttpResponse
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Serves tiles from the test assets without a network, as whatever body a test asks for. */
private class FakeTileClient(private val assets: AssetManager) : ValhallaHttpClient() {

  data class Request(val path: String, val rangeSize: Long, val acceptGzip: Boolean)

  /** Turns a stored tile into the body sent for it. */
  @Volatile var body: (ByteArray) -> ByteArray = { it }

  /** Answers a range with the whole file, like a server that ignores Range. */
  @Volatile var ignoreRange = false

  val requests = CopyOnWriteArrayList<Request>()

  fun tile(path: String) = assets.open("valhalla_tiles/$path").use { it.readBytes() }

  override fun get(
      url: String,
      rangeOffset: Long,
      rangeSize: Long,
      acceptGzip: Boolean
  ): ValhallaHttpResponse {
    val path = url.substringAfter(BASE_URL)
    requests += Request(path, rangeSize, acceptGzip)
    val bytes =
        try {
          if (path == TAR) assets.open(TAR).use { it.readBytes() } else tile(path)
        } catch (e: IOException) {
          return ValhallaHttpResponse.failure(404)
        }
    val sent =
        when {
          rangeSize == 0L -> body(bytes)
          ignoreRange -> bytes
          else -> bytes.copyOfRange(rangeOffset.toInt(), (rangeOffset + rangeSize).toInt())
        }
    return ValhallaHttpResponse(success = true, httpCode = 200, lastModified = 0, body = sent)
  }

  companion object {
    const val BASE_URL = "http://tiles.invalid/"
    const val TAR = "valhalla_tiles.tar"
  }
}

/** How the shared C++ tile getter stores what a client hands it. */
@RunWith(AndroidJUnit4::class)
class ValhallaTileUrlTest {

  private lateinit var context: Context
  private lateinit var tilesDir: File
  private lateinit var client: FakeTileClient

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    tilesDir = File(context.cacheDir, "tile-url-${UUID.randomUUID()}").apply { mkdirs() }
    client = FakeTileClient(context.assets)
  }

  @After
  fun tearDown() {
    if (::tilesDir.isInitialized) tilesDir.deleteRecursively()
  }

  /** Routes with a new actor, as a fresh app launch does, and returns the trip status. */
  private fun route(
      gzipped: Boolean,
      url: String = "${FakeTileClient.BASE_URL}{tilePath}"
  ): String? {
    val config = ValhallaConfigFactory.usingTileUrl(url, tilesDir.absolutePath, gzipped)
    val file = ValhallaFile(context, "tile-url.json")
    ValhallaConfigManager(context, file).writeConfig(config)
    val actor = ValhallaActor(file.absolutePath(), client)
    try {
      return JSONObject(actor.route(ANDORRA_ROUTE))
          .optJSONObject("trip")
          ?.getString("status_message")
    } finally {
      actor.close()
    }
  }

  private fun storedTiles(): Map<String, ByteArray> =
      tilesDir
          .walkTopDown()
          .filter { it.isFile && (it.name.endsWith(".gph") || it.name.endsWith(".gph.gz")) }
          .associate { it.relativeTo(tilesDir).path to it.readBytes() }

  private fun assertStoredGzipped() {
    val tiles = storedTiles()
    assertFalse("nothing was stored", tiles.isEmpty())
    for ((path, stored) in tiles) {
      assertTrue(path, path.endsWith(".gph.gz"))
      assertArrayEquals(path, client.tile(path.removeSuffix(".gz")), LocalTileServer.gunzip(stored))
    }
  }

  private fun assertStoredRaw() {
    val tiles = storedTiles()
    assertFalse("nothing was stored", tiles.isEmpty())
    for ((path, stored) in tiles) {
      assertTrue(path, path.endsWith(".gph"))
      assertArrayEquals(path, client.tile(path), stored)
    }
  }

  @Test
  fun testKeepsGzipBodiesWithTheFlagOn() {
    client.body = LocalTileServer::gzip

    assertEquals(FOUND, route(gzipped = true))

    assertTrue(client.requests.all { it.acceptGzip })
    assertStoredGzipped()
  }

  @Test
  fun testCompressesPlainBodiesWithTheFlagOn() {
    assertEquals(FOUND, route(gzipped = true))

    assertStoredGzipped()
  }

  @Test
  fun testInflatesGzipBodiesWithTheFlagOff() {
    client.body = LocalTileServer::gzip

    assertEquals(FOUND, route(gzipped = false))

    assertTrue(client.requests.none { it.acceptGzip })
    assertStoredRaw()
  }

  @Test
  fun testRejectsBodiesThatAreNotOneWholeTile() {
    val bodies =
        mapOf<String, (ByteArray) -> ByteArray>(
            "empty" to { ByteArray(0) },
            "an html page" to { "<html>${"Sign in. ".repeat(100)}</html>".toByteArray() },
            "a truncated tile" to { it.copyOf(it.size / 2) },
            "gzip with a corrupt tail" to
                {
                  LocalTileServer.gzip(it).also { gz ->
                    gz[gz.size - 20] = (gz[gz.size - 20].toInt() xor 0xff).toByte()
                  }
                },
            "gzip twice" to { LocalTileServer.gzip(LocalTileServer.gzip(it)) },
        )
    for ((name, body) in bodies) {
      for (gzipped in listOf(true, false)) {
        client.body = body
        assertNull("$name, gzip $gzipped", route(gzipped))
        assertTrue("$name was stored, gzip $gzipped", storedTiles().isEmpty())
      }
    }
  }

  /**
   * Valhalla 3.6.3 checked a gzipped tile's checksum before inflating it, so this failed with 446.
   */
  @Test
  fun testLaterLaunchFetchesMissingTiles() {
    route(gzipped = true)
    val first = storedTiles().keys
    first.forEach { File(tilesDir, it).delete() }

    assertEquals(FOUND, route(gzipped = true))

    assertEquals(first, storedTiles().keys)
  }

  @Test
  fun testRemoteTarIsStoredRaw() {
    assertEquals(FOUND, route(gzipped = true, url = FakeTileClient.BASE_URL + FakeTileClient.TAR))

    assertTrue(client.requests.all { it.rangeSize > 0 && !it.acceptGzip })
    assertStoredRaw()
  }

  @Test
  fun testRejectsARangeTheServerIgnored() {
    client.ignoreRange = true

    assertNull(route(gzipped = false, url = FakeTileClient.BASE_URL + FakeTileClient.TAR))

    assertTrue(storedTiles().isEmpty())
  }

  private companion object {
    const val FOUND = "Found route between points"
    const val ANDORRA_ROUTE =
        "{\"locations\":[{\"lat\":42.5063,\"lon\":1.5218},{\"lat\":42.5086,\"lon\":1.5394}],\"costing\":\"auto\",\"units\":\"miles\"}"
  }
}
