package com.valhalla.valhalla.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Fetches tiles over HTTP for the C++ tile getter, when the config sets `mjolnir.tile_url`.
 *
 * This is the Android half of `ValhallaMobileHttpClient`, the interface `src/wrapper` declares for
 * both platforms. iOS implements it directly in Obj-C++ with `NSURLConnection`; here C++ calls back
 * into [get] and [head] through JNI.
 *
 * [java.net.HttpURLConnection] rather than a third-party client, so that consumers of the published
 * library inherit no HTTP dependency of ours — the same reason iOS uses the platform's own stack.
 *
 * Neither method throws. The tile getter has no way to receive an exception, and a failed fetch is
 * an ordinary event — a tile that is simply not on the server — so every failure comes back as an
 * unsuccessful [ValhallaHttpResponse] instead.
 *
 * Two things a caller has to know:
 * * The consuming app needs `<uses-permission android:name="android.permission.INTERNET" />`. This
 *   library does not declare it, because that would force the permission on every consumer,
 *   including the offline-only ones. Without it every fetch fails.
 * * Requests are synchronous, on whichever thread ran the routing action. Routing on the main
 *   thread with a `tile_url` config therefore trips `NetworkOnMainThreadException`, which is
 *   reported here as a failed fetch.
 */
internal class ValhallaHttpClient(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {

  /**
   * Fetches a tile, or a byte range of one.
   *
   * @param url the tile URL, already filled in by valhalla.
   * @param rangeOffset first byte to request. Only used when [rangeSize] is positive.
   * @param rangeSize how many bytes to request; `0` asks for the whole resource.
   * @param gzip whether the tileset is served gzip-compressed, from `mjolnir.tile_url_gz`.
   *   Passed per request rather than held here, so this object stays stateless.
   */
  fun get(url: String, rangeOffset: Long, rangeSize: Long, gzip: Boolean): ValhallaHttpResponse =
      perform(
          url,
          method = "GET",
          headerMask = 0,
          gzip = gzip,
          // Only when we asked for an encoding. With the header unset the platform
          // negotiates and inflates, so Content-Encoding then describes what crossed the
          // wire rather than what is in hand. A range request says nothing either way.
          expectedEncoding = if (gzip && rangeSize == 0L) "gzip" else null,
      ) { connection ->
        if (rangeSize > 0) {
          // Inclusive on both ends, so the last byte is offset + size - 1.
          connection.setRequestProperty(
              "Range", "bytes=$rangeOffset-${rangeOffset + rangeSize - 1}")
        }
      }

  /**
   * Asks for a tile's headers without its body, to find out whether a cached copy is stale.
   *
   * @param url the tile URL.
   * @param headerMask which headers the caller wants. Only [HEADER_LAST_MODIFIED] is understood;
   *   anything else is ignored, and the corresponding field is left at zero.
   */
  fun head(url: String, headerMask: Int): ValhallaHttpResponse =
      perform(url, method = "HEAD", headerMask = headerMask) {}

  private fun perform(
      url: String,
      method: String,
      headerMask: Int,
      gzip: Boolean = false,
      expectedEncoding: String? = null,
      configure: (HttpURLConnection) -> Unit,
  ): ValhallaHttpResponse {
    var connection: HttpURLConnection? = null
    return try {
      connection =
          (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            // Set only when valhalla wants the compressed bytes. HttpURLConnection inflates
            // transparently exactly when it chose the encoding itself, so:
            //
            //  * gzip on  -> we set it, and the body arrives compressed, which is what
            //    `tile_url_gz: true` promises valhalla.
            //  * gzip off -> we leave it alone, and HttpURLConnection negotiates gzip and
            //    inflates for us. Compressed on the wire, uncompressed in hand.
            //
            // The previous code sent `identity` in the second case, which is where the real
            // cost was: an uncompressed tile is roughly 2.7x the bytes, and on a phone that
            // is somebody's cellular data. iOS never had that problem because NSURLSession
            // always negotiates gzip -- and always inflates, which is why it cannot serve
            // the first case at all.
            if (gzip) {
              setRequestProperty("Accept-Encoding", "gzip")
            }
            configure(this)
          }

      val httpCode = connection.responseCode
      if (httpCode !in HTTP_OK_RANGE) {
        return ValhallaHttpResponse.failure(httpCode)
      }

      val lastModified =
          if (headerMask and HEADER_LAST_MODIFIED != 0) {
            // getHeaderFieldDate reads all three date formats HTTP allows, and answers in
            // milliseconds. The tile getter counts in seconds.
            TimeUnit.MILLISECONDS.toSeconds(connection.getHeaderFieldDate("Last-Modified", 0L))
          } else {
            0L
          }

      // Content negotiation is a request, not a guarantee, and valhalla cannot tell a
      // wrongly-encoded body from a corrupt one: it inflates according to `tile_url_gz` and
      // reports a decompression failure whatever actually arrived. Checking here turns a
      // confusing tile error into an ordinary failed fetch.
      //
      // Not hypothetical. A CDN in front of this tileset answers `zstd` to a client that
      // offers `gzip, br, zstd`, and plain `identity` to one that offers only `br` -- so an
      // edited Accept-Encoding, or a proxy that rewrites it, silently produces bytes valhalla
      // will try to gunzip.
      if (expectedEncoding != null) {
        val encoding =
            connection.getHeaderField("Content-Encoding")?.trim()?.lowercase() ?: "identity"
        if (encoding != expectedEncoding) {
          return ValhallaHttpResponse.failure(httpCode)
        }
      }

      val body = if (method == "GET") connection.inputStream.use { it.readBytes() } else null

      ValhallaHttpResponse(
          success = true, httpCode = httpCode, lastModified = lastModified, body = body)
    } catch (e: IOException) {
      // A refused connection, a timeout, a DNS failure, or a body that ended early.
      ValhallaHttpResponse.failure()
    } catch (e: SecurityException) {
      // The consuming app is missing the INTERNET permission.
      ValhallaHttpResponse.failure()
    } catch (e: RuntimeException) {
      // A malformed URL from the config, or NetworkOnMainThreadException. Neither may reach the
      // C++ caller, which has no way to handle a Java exception.
      ValhallaHttpResponse.failure()
    } finally {
      connection?.disconnect()
    }
  }

  companion object {
    /** Matches `tile_getter_t::kHeaderLastModified` in valhalla's `baldr/tilegetter.h`. */
    const val HEADER_LAST_MODIFIED: Int = 1

    private const val DEFAULT_TIMEOUT_MILLIS = 10_000

    private val HTTP_OK_RANGE = 200..299
  }
}
