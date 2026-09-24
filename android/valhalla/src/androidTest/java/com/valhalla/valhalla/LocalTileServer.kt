package com.valhalla.valhalla

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/** Serves the fixture tiles over loopback HTTP, gzipping them when the client accepts it. */
class LocalTileServer(private val assets: AssetManager) : Closeable {

  /** The `Accept-Encoding` of every request, or null when it had none. */
  val acceptEncodings = CopyOnWriteArrayList<String?>()

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
    acceptEncodings += acceptEncoding

    // "GET /2/000/762/485.gph HTTP/1.1"
    val path = requestLine.split(" ").getOrElse(1) { "/" }.trimStart('/')
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
    val compress = acceptEncoding?.contains("gzip") == true
    val body = if (compress) gzip(tile) else tile
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

    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
  }
}
