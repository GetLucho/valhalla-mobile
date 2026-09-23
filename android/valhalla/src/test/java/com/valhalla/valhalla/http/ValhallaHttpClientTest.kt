package com.valhalla.valhalla.http

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * These run on the JVM rather than on a device: [ValhallaHttpClient] is plain
 * `java.net.HttpURLConnection` and touches no native library and no Android
 * [android.content.Context]. The server is the JDK's own, so the tests add no dependency.
 *
 * The contract under test is narrow and easy to get backwards. Tiles cross the wire compressed
 * either way — what changes is whether they are still compressed when valhalla gets them:
 * * `gzip = true` — the header is set deliberately, so HttpURLConnection does not inflate, and
 *   valhalla receives the gzip stream `tile_url_gz: true` promises it.
 * * `gzip = false` — the header is left alone, so HttpURLConnection negotiates gzip itself and
 *   inflates transparently. Compressed on the wire, uncompressed in hand.
 */
class ValhallaHttpClientTest {

  private lateinit var server: HttpServer
  private lateinit var baseUrl: String

  // Written by the test thread, read by the server's handler thread, and the other way for
  // sentAcceptEncoding. The handler is already running when @Before returns, so without
  // @Volatile a handler can legally serve the previous payload and the assertions can read a
  // stale header.
  /** `Accept-Encoding` from the most recent request. */
  @Volatile private var sentAcceptEncoding: String? = null

  /** The tile as stored, uncompressed. The server compresses it when asked to. */
  @Volatile private var payload: ByteArray = "tile".toByteArray()

  /**
   * Set only by tests that need a server answering with the wrong thing. Left null, the server
   * negotiates honestly from `Accept-Encoding`, the way a real one does.
   */
  @Volatile private var forcedEncoding: String? = null

  @Before
  fun startServer() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/tile") { exchange ->
      val accept = exchange.requestHeaders.getFirst("Accept-Encoding")
      sentAcceptEncoding = accept

      val encoding = forcedEncoding ?: if (accept?.contains("gzip") == true) "gzip" else "identity"
      val body = if (encoding == "gzip") gzip(payload) else payload

      if (encoding != "identity") exchange.responseHeaders.add("Content-Encoding", encoding)
      // Content-Length describes the bytes actually written, which for a gzipped body is the
      // compressed length -- the same thing a real tile server reports.
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    baseUrl = "http://127.0.0.1:${server.address.port}/tile"
  }

  @After
  fun stopServer() {
    server.stop(0)
  }

  private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
  }

  @Test
  fun `asks for gzip when valhalla wants the compressed bytes`() {
    ValhallaHttpClient().get(baseUrl, rangeOffset = 0, rangeSize = 0, gzip = true)

    assertEquals("gzip", sentAcceptEncoding)
  }

  /**
   * That the compressed bytes reach the caller intact.
   *
   * This does not prove *why* on Android. The production claim is OkHttp-specific —
   * HttpURLConnection inflates transparently exactly when it chose the encoding itself — and
   * the JDK's implementation never inflates at all, so this passes either way. Only an
   * androidTest against the real client can cover that half.
   */
  @Test
  fun `hands back the compressed bytes when gzip is asked for`() {
    payload = "a tile, long enough that compressing it changes the length".toByteArray()

    val response = ValhallaHttpClient().get(baseUrl, rangeOffset = 0, rangeSize = 0, gzip = true)

    assertTrue(response.success)
    assertArrayEquals(gzip(payload), response.body)
    assertFalse("must not be inflated", payload.contentEquals(response.body))
  }

  /**
   * The other half: the header is left alone so the platform can negotiate for itself.
   *
   * What Android then does — send `Accept-Encoding: gzip` and inflate transparently, so the tile
   * crosses the wire compressed and arrives uncompressed — cannot be asserted here. Android's
   * HttpURLConnection is OkHttp-backed and does that; the JDK's sends no Accept-Encoding at all
   * and leaves the body alone. Both agree on the part this class controls, which is that nothing
   * is set here, so that is what the test pins.
   */
  @Test
  fun `leaves the encoding to the platform when gzip is not asked for`() {
    payload = "a tile, long enough that compressing it changes the length".toByteArray()

    val response = ValhallaHttpClient().get(baseUrl, rangeOffset = 0, rangeSize = 0, gzip = false)

    assertTrue(response.success)
    assertNull("must not pin an encoding of its own", sentAcceptEncoding)
    assertArrayEquals(payload, response.body)
  }

  @Test
  fun `fails when gzip was asked for and the server sent identity`() {
    forcedEncoding = "identity"

    val response = ValhallaHttpClient().get(baseUrl, rangeOffset = 0, rangeSize = 0, gzip = true)

    assertFalse(response.success)
    assertNull(response.body)
  }

  /**
   * No check when the header was left to the platform: `Content-Encoding` then describes what
   * crossed the wire, not what is in hand, so enforcing it would fail every ordinary fetch.
   */
  @Test
  fun `does not check the encoding when gzip was not asked for`() {
    forcedEncoding = "identity"

    val response = ValhallaHttpClient().get(baseUrl, rangeOffset = 0, rangeSize = 0, gzip = false)

    assertTrue(response.success)
    assertArrayEquals(payload, response.body)
  }

  /**
   * A range request must not ask for gzip, even with `tile_url_gz` on.
   *
   * The response would be unverifiable — a 206 is served from the identity representation, so
   * its `Content-Encoding` says nothing about what was requested — and asking opts out of the
   * platform's transparent inflation. A proxy that compresses the range anyway then hands
   * valhalla a gzip stream it will inflate twice, which is a null deref, not an error.
   */
  @Test
  fun `does not ask for gzip on a range request`() {
    ValhallaHttpClient().get(baseUrl, rangeOffset = 0, rangeSize = 4, gzip = true)

    assertNull(sentAcceptEncoding)
  }

  /**
   * And correspondingly does not enforce an encoding it never asked for.
   */
  @Test
  fun `does not check the encoding on a range request`() {
    forcedEncoding = "identity"

    val response = ValhallaHttpClient().get(baseUrl, rangeOffset = 0, rangeSize = 4, gzip = true)

    assertTrue(response.success)
  }
}
