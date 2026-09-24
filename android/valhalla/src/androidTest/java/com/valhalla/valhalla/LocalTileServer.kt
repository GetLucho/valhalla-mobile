package com.valhalla.valhalla

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

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
    /** Send the tile gzipped with a corrupt byte near the end, and no `Content-Encoding`. */
    CORRUPT_GZIP_TAIL,
    /** Answer 200 with a page that isn't a tile, like a captive portal. */
    NOT_A_TILE,
    /** Answer a Range request with 200 and the whole file. */
    IGNORE_RANGE,
  }

  /** One request: the path, its `Accept-Encoding` header, and whether it asked for a range. */
  data class Request(val path: String, val acceptEncoding: String?, val ranged: Boolean)

  @Volatile var mode = Mode.NEGOTIATE

  val requests = CopyOnWriteArrayList<Request>()

  private val remoteTar = remoteTar()

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
    fun header(name: String) =
        headers
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
    val acceptEncoding = header("accept-encoding")
    val range = header("range")?.let(::parseRange)
    // "GET /2/000/762/485.gph HTTP/1.1"
    val path = requestLine.split(" ").getOrElse(1) { "/" }.trimStart('/')
    requests += Request(path, acceptEncoding, range != null)

    val stored =
        if (path == REMOTE_TAR_PATH) remoteTar
        else
            try {
              fixture(path)
            } catch (e: IOException) {
              null
            }
    val out = client.getOutputStream()
    if (stored == null) {
      out.write(
          "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
      return
    }

    if (range != null && mode != Mode.IGNORE_RANGE) {
      val last = minOf(range.last, stored.size - 1)
      val slice = stored.copyOfRange(range.first, last + 1)
      out.write(
          ("HTTP/1.1 206 Partial Content\r\nContent-Length: ${slice.size}\r\n" +
                  "Content-Range: bytes ${range.first}-$last/${stored.size}\r\n" +
                  "Connection: close\r\n\r\n")
              .toByteArray())
      out.write(slice)
      out.flush()
      return
    }

    val compress = mode == Mode.NEGOTIATE && acceptEncoding?.contains("gzip") == true
    val body =
        when (mode) {
          Mode.NEGOTIATE -> if (compress) gzip(stored) else stored
          Mode.IDENTITY,
          Mode.IGNORE_RANGE -> stored
          Mode.PRE_GZIPPED -> gzip(stored)
          Mode.EMPTY -> ByteArray(0)
          Mode.CORRUPT_GZIP_TAIL ->
              gzip(stored).also { it[it.size - 20] = (it[it.size - 20].toInt() xor 0xff).toByte() }
          Mode.NOT_A_TILE ->
              ("<html><body>" + "Sign in to continue. ".repeat(100) + "</body></html>")
                  .toByteArray()
        }
    val encoding = if (compress) "Content-Encoding: gzip\r\n" else ""
    out.write(
        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n${encoding}Connection: close\r\n\r\n"
            .toByteArray())
    out.write(body)
    out.flush()
  }

  /**
   * A tar of the fixture tiles, laid out the way valhalla reads one remotely: index.bin first,
   * giving each tile's offset, id, and size.
   */
  private fun remoteTar(): ByteArray {
    val paths = tilePaths("").sorted()
    val index = ByteBuffer.allocate(paths.size * 16).order(ByteOrder.LITTLE_ENDIAN)
    val body = ByteArrayOutputStream()
    var offset = 512 + padded(paths.size * 16)
    for (path in paths) {
      val tile = fixture(path)
      index.putLong((offset + 512).toLong()).putInt(tileId(path)).putInt(tile.size)
      body.write(tarHeader(path, tile.size))
      body.write(tile)
      body.write(ByteArray(padded(tile.size) - tile.size))
      offset += 512 + padded(tile.size)
    }
    val tar = ByteArrayOutputStream()
    tar.write(tarHeader("index.bin", index.capacity()))
    tar.write(index.array())
    tar.write(ByteArray(padded(index.capacity()) - index.capacity()))
    tar.write(body.toByteArray())
    tar.write(ByteArray(1024))
    return tar.toByteArray()
  }

  private fun tilePaths(dir: String): List<String> =
      assets
          .list(if (dir.isEmpty()) "valhalla_tiles" else "valhalla_tiles/$dir")
          .orEmpty()
          .flatMap {
            val path = if (dir.isEmpty()) it else "$dir/$it"
            if (it.endsWith(".gph")) listOf(path) else tilePaths(path)
          }

  companion object {
    /** The path of a remote tar built from the fixture tiles. */
    const val REMOTE_TAR_PATH = "remote.tar"

    fun gzip(bytes: ByteArray): ByteArray {
      val out = ByteArrayOutputStream()
      GZIPOutputStream(out).use { it.write(bytes) }
      return out.toByteArray()
    }

    /** Inflates one gzip layer, checking its CRC and length. */
    fun gunzip(bytes: ByteArray): ByteArray =
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    /** "bytes=0-511", inclusive on both ends. */
    private fun parseRange(value: String): IntRange? {
      val bounds = value.removePrefix("bytes=").split("-").mapNotNull { it.toIntOrNull() }
      return if (value.startsWith("bytes=") && bounds.size == 2 && bounds[0] <= bounds[1])
          bounds[0]..bounds[1]
      else null
    }

    private fun padded(size: Int) = (size + 511) / 512 * 512

    /** "2/000/762/485.gph" is level 2, tile 762485. */
    private fun tileId(path: String): Int {
      val parts = path.removeSuffix(".gph").split("/")
      return parts[0].toInt() or (parts.drop(1).joinToString("").toInt() shl 3)
    }

    private fun tarHeader(name: String, size: Int): ByteArray {
      val header = ByteArray(512)
      fun put(text: String, offset: Int) = text.toByteArray().copyInto(header, offset)
      put(name, 0)
      put("0000644", 100)
      put("0000000", 108)
      put("0000000", 116)
      put(String.format("%011o", size), 124)
      put("00000000000", 136)
      put("0", 156)
      put("ustar", 257)
      put("00", 263)
      put("        ", 148)
      val sum = header.sumOf { it.toInt() and 0xff }
      put(String.format("%06o", sum), 148)
      header[154] = 0
      return header
    }
  }
}
