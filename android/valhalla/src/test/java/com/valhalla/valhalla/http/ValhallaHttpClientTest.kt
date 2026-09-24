package com.valhalla.valhalla.http

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests against the JDK's HTTP server. [ValhallaHttpClient] only uses `HttpURLConnection`, so
 * no device or native library is needed.
 *
 * The JDK's HttpURLConnection never negotiates or inflates gzip on its own, unlike Android's.
 * `ValhallaTileUrlTest` covers Android's behavior on a device.
 */
class ValhallaHttpClientTest {

  private lateinit var server: HttpServer
  private lateinit var baseUrl: String

  // Shared between the test thread and the server's handler thread.
  /** `Accept-Encoding` from the most recent request. */
  @Volatile private var sentAcceptEncoding: String? = null

  /** The stored tile, uncompressed. The server gzips it when asked. */
  @Volatile
  private var payload: ByteArray = "a tile, long enough that gzip changes it".toByteArray()

  /** Overrides the response encoding, for a server that ignores Accept-Encoding. */
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
  fun `asks for gzip when a gzip body is accepted`() {
    val response = ValhallaHttpClient().get(baseUrl, 0, 0, acceptGzip = true)

    assertEquals("gzip", sentAcceptEncoding)
    assertTrue(response.success)
    assertArrayEquals(gzip(payload), response.body)
  }

  /** The wrapper compresses a plain body, so this is not a failure. */
  @Test
  fun `returns a plain body when the server ignores the request for gzip`() {
    forcedEncoding = "identity"

    val response = ValhallaHttpClient().get(baseUrl, 0, 0, acceptGzip = true)

    assertTrue(response.success)
    assertArrayEquals(payload, response.body)
  }

  @Test
  fun `leaves the encoding to the platform when gzip is not accepted`() {
    val response = ValhallaHttpClient().get(baseUrl, 0, 0, acceptGzip = false)

    assertNull(sentAcceptEncoding)
    assertTrue(response.success)
    assertArrayEquals(payload, response.body)
  }

  @Test
  fun `asks for identity on a range request`() {
    ValhallaHttpClient().get(baseUrl, 0, 4, acceptGzip = false)

    assertEquals("identity", sentAcceptEncoding)
  }
}
