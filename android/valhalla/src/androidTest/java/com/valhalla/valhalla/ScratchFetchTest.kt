package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import com.valhalla.valhalla.http.ValhallaHttpClient
import com.valhalla.valhalla.http.ValhallaHttpResponse
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Cases for the upstream deadline and retry PR: failed fetches are retried by the next action. */
@RunWith(AndroidJUnit4::class)
class ScratchFetchTest {

  private lateinit var context: Context
  private lateinit var tilesDir: File

  /** Answers each request from [answers] in turn, then with the real tile. */
  private inner class ScriptedClient(vararg answers: (ByteArray) -> ValhallaHttpResponse) :
      ValhallaHttpClient() {
    private val script = ArrayDeque(answers.toList())
    val requests = CopyOnWriteArrayList<String>()

    override fun get(
        url: String,
        rangeOffset: Long,
        rangeSize: Long,
        acceptGzip: Boolean,
    ): ValhallaHttpResponse {
      requests += url
      val path = url.substringAfter(BASE)
      val tile =
          try {
            context.assets.open("valhalla_tiles/$path").use { it.readBytes() }
          } catch (e: Exception) {
            return ValhallaHttpResponse.failure(404)
          }
      return script.removeFirstOrNull()?.invoke(tile) ?: ok(tile)
    }
  }

  private fun ok(body: ByteArray) =
      ValhallaHttpResponse(success = true, httpCode = 200, lastModified = 0, body = body)

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    tilesDir = File(context.cacheDir, "scratch-${UUID.randomUUID()}").apply { mkdirs() }
  }

  @After
  fun tearDown() {
    tilesDir.deleteRecursively()
  }

  private fun actor(url: String, client: ValhallaHttpClient, timeoutSeconds: Double? = null): ValhallaActor {
    val config = ValhallaConfigFactory.usingTileUrl(url, tilesDir.absolutePath, false)
    val file = ValhallaFile(context, "scratch.json")
    ValhallaConfigManager(context, file).writeConfig(config)
    if (timeoutSeconds != null) {
      val written = File(file.absolutePath())
      val json = JSONObject(written.readText())
      json.getJSONObject("mjolnir").put("tile_url_timeout", timeoutSeconds)
      written.writeText(json.toString())
    }
    return ValhallaActor(file.absolutePath(), client)
  }

  private fun message(envelope: String) = JSONObject(envelope).optString("message")

  @Test
  fun serverErrorThrowsAndRetrySucceeds() {
    val client = ScriptedClient({ ValhallaHttpResponse.failure(500) })
    actor("$BASE{tilePath}", client).use { actor ->
      assertEquals("valhalla-mobile: tile fetch failed", message(actor.ensureTileCached(2, TILE)))
      assertEquals("true", actor.ensureTileCached(2, TILE))
      assertEquals(2, client.requests.size)
    }
  }

  @Test
  fun notFoundStaysAbsent() {
    val client = ScriptedClient()
    actor("$BASE{tilePath}", client).use { actor ->
      assertEquals("false", actor.ensureTileCached(2, MISSING))
      assertEquals("false", actor.ensureTileCached(2, MISSING))
      assertEquals(1, client.requests.size)
    }
  }

  @Test
  fun loginPageThrowsAndRetrySucceeds() {
    val client = ScriptedClient({ ok("<html>Sign in</html>".repeat(40).toByteArray()) })
    actor("$BASE{tilePath}", client).use { actor ->
      assertEquals("valhalla-mobile: tile fetch failed", message(actor.ensureTileCached(2, TILE)))
      assertEquals("true", actor.ensureTileCached(2, TILE))
    }
  }

  @Test
  fun routeAroundAFailedTileNeverReports171() {
    val paths = listOf("0/003/015.gph", "1/047/701.gph", "2/000/762/485.gph", "2/000/762/486.gph",
        "2/000/763/925.gph", "2/000/763/926.gph", "2/000/763/927.gph")
    val outcomes = mutableMapOf<String, String>()
    for (failing in paths) {
      tilesDir.deleteRecursively(); tilesDir.mkdirs()
      val client = object : ValhallaHttpClient() {
        override fun get(url: String, rangeOffset: Long, rangeSize: Long, acceptGzip: Boolean): ValhallaHttpResponse {
          val path = url.substringAfter(BASE)
          if (path == failing) return ValhallaHttpResponse.failure(0)
          return try { ok(context.assets.open("valhalla_tiles/$path").use { it.readBytes() }) } catch (e: Exception) { ValhallaHttpResponse.failure(404) }
        }
      }
      actor("$BASE{tilePath}", client).use { actor ->
        val first = JSONObject(actor.route(ROUTE))
        outcomes[failing] = first.optJSONObject("trip")?.getString("status_message") ?: first.optString("message")
      }
    }
    android.util.Log.i("ScratchFetchTest", "outcomes: $outcomes")
    assertTrue("$outcomes", outcomes.values.all { it == "Found route between points" || it == "valhalla-mobile: tile fetch failed" })
    assertTrue("$outcomes", outcomes.values.any { it == "Found route between points" })
  }

  @Test
  fun routeReportsFetchFailureOrRoutesAroundItThenRecovers() {
    val client = ScriptedClient({ ValhallaHttpResponse.failure(503) })
    actor("$BASE{tilePath}", client).use { actor ->
      val first = JSONObject(actor.route(ROUTE))
      val status = first.optJSONObject("trip")?.getString("status_message") ?: first.optString("message")
      assertTrue(status, status == "Found route between points" || status == "valhalla-mobile: tile fetch failed")
      val retry = JSONObject(actor.route(ROUTE))
      assertEquals("Found route between points", retry.getJSONObject("trip").getString("status_message"))
    }
  }

  @Test
  fun slowButLiveTileLands() {
    val tile = context.assets.open("valhalla_tiles/2/000/762/485.gph").use { it.readBytes() }
    val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true) {
      while (!server.isClosed) {
        val socket = try { server.accept() } catch (e: Exception) { break }
        thread(isDaemon = true) {
          socket.use {
            val reader = it.getInputStream().bufferedReader()
            while (reader.readLine()?.isNotEmpty() == true) {}
            val out = it.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${tile.size}\r\nConnection: close\r\n\r\n".toByteArray())
            try {
              tile.toList().chunked(10_000).forEach { chunk -> out.write(chunk.toByteArray()); out.flush(); Thread.sleep(100) }
            } catch (e: Exception) {}
          }
        }
      }
    }
    try {
      actor("http://127.0.0.1:${server.localPort}/{tilePath}", ValhallaHttpClient(), 1.0).use { actor ->
        val started = System.nanoTime()
        assertEquals("true", actor.ensureTileCached(2, TILE))
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("took $seconds s", seconds > 1.5)
        assertTrue(File(tilesDir, "2/000/762/485.gph").isFile)
      }
    } finally {
      server.close()
    }
  }

  private companion object {
    const val BASE = "http://tiles.invalid/"
    const val TILE = 762485
    const val MISSING = 762484
    const val ROUTE =
        "{\"locations\":[{\"lat\":42.5063,\"lon\":1.5218},{\"lat\":42.5086,\"lon\":1.5394}],\"costing\":\"auto\",\"units\":\"miles\"}"
  }
}
