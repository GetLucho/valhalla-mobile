package com.valhalla.valhalla

import com.valhalla.valhalla.http.ValhallaHttpClient
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

internal interface ValhallaActorProviding : Closeable {
  fun route(request: String): String

  fun traceRoute(request: String): String

  fun traceAttributes(request: String): String

  fun height(request: String): String

  fun matrix(request: String): String

  fun tilesCovering(latitude: Double, longitude: Double): String

  fun ensureTileCached(level: Int, tileId: Int): Boolean

  fun cancel()

  fun resume()
}

/**
 * Access with raw unchecked strings to the Valhalla routing engine. This class is available, but
 * not recommended for general use.
 *
 * Holds one native actor for the lifetime of this instance, the way iOS holds one per
 * `ValhallaWrapper`. Building it parses the config and opens the tile extract, so reuse one
 * instance across many requests rather than creating one per call. That work happens here in the
 * constructor, so the config file is read before another instance can overwrite it. A config that
 * cannot be read is not fatal: the first call retries and reports through the response envelope,
 * as it always has.
 *
 * Call [close] to release the native actor; nothing else frees it.
 *
 * @property configPath
 * @param httpClient fetches tiles when the config sets `mjolnir.tile_url`. Pass null to turn
 *   fetching off, which leaves such a config reporting every fetch as a failure.
 */
internal class ValhallaActor(
    configPath: String,
    httpClient: ValhallaHttpClient? = ValhallaHttpClient(),
) : ValhallaActorProviding {
  private val valhallaKotlin = ValhallaKotlin()
  private val lock = Any()

  /** Zero once closed. Guarded by [lock]. */
  private var handle: Long = valhallaKotlin.createActor(configPath, httpClient)

  init {
    check(handle != 0L) { "could not allocate the native Valhalla actor" }
  }

  /**
   * Run a route request to the Valhalla routing engine. This assumes your config path is valid,
   * tiles exist and your request string is valid.
   *
   * @param request
   * @return
   */
  override fun route(request: String): String = perform(request, valhallaKotlin::route)

  /**
   * Run a `trace_route` request, map-matching a GPS trace onto the road network and returning a
   * route along the matched path. Same assumptions as [route].
   *
   * @param request
   * @return
   */
  override fun traceRoute(request: String): String =
      perform(request, valhallaKotlin::traceRoute)

  /**
   * Run a `trace_attributes` request, map-matching a GPS trace onto the road network and returning
   * the attributes of every edge along the matched path. Same assumptions as [route].
   *
   * @param request
   * @return
   */
  override fun traceAttributes(request: String): String =
      perform(request, valhallaKotlin::traceAttributes)

  /** Run a `height` request to sample heights under a shape. Same assumptions as [route]. */
  override fun height(request: String): String = perform(request, valhallaKotlin::height)

  /**
   * Run a `sources_to_targets` request, computing a matrix of costs and times between every
   * source and every target. Same assumptions as [route].
   */
  override fun matrix(request: String): String = perform(request, valhallaKotlin::matrix)

  /**
   * The tiles covering a coordinate, one per hierarchy level, as JSON.
   *
   * From `TileHierarchy::levels()` and `GraphTile::FileSuffix`, so a consumer needs no copy of
   * the grid arithmetic. Empty for a coordinate that is not on the planet.
   */
  override fun tilesCovering(latitude: Double, longitude: Double): String =
      synchronized(lock) {
        check(handle != 0L) { "the Valhalla actor is closed" }
        String(valhallaKotlin.tilesCovering(handle, latitude, longitude), Charsets.UTF_8)
      }

  /**
   * Ensure one tile is in `mjolnir.tile_dir`, fetching it through valhalla if it is not.
   *
   * False for a tile the origin does not have, which is normal coverage rather than a failure,
   * and false when the fetch was cancelled or hit the deadline.
   */
  override fun ensureTileCached(level: Int, tileId: Int): Boolean =
      synchronized(lock) {
        check(handle != 0L) { "the Valhalla actor is closed" }
        valhallaKotlin.ensureTileCached(handle, level, tileId)
      }

  /**
   * Ask the action running now to stop at its next tile fetch. Sticky until [resume].
   *
   * Deliberately NOT synchronized: every other method holds [lock] for the duration of its
   * native call, so taking it here would mean waiting for the very thing being cancelled. The
   * flag lives on the native handle, which outlives any one actor, and the handle field is
   * only ever zeroed by [close] -- a cancel racing a close sets a flag nobody reads.
   */
  override fun cancel() {
    val current = handle
    if (current != 0L) valhallaKotlin.setCancelled(current, true)
  }

  override fun resume() {
    val current = handle
    if (current != 0L) valhallaKotlin.setCancelled(current, false)
  }

  /**
   * Release the native actor. Safe to call more than once; later calls do nothing.
   *
   * Any action attempted after this throws [IllegalStateException].
   */
  override fun close() {
    synchronized(lock) {
      if (handle != 0L) {
        valhallaKotlin.deleteActor(handle)
        handle = 0L
      }
    }
  }

  /**
   * The actor is not safe to use from several threads at once, so every call is serialised here —
   * the same guarantee `@synchronized(self)` gives the iOS wrapper. Holding the lock across the
   * native call is also what keeps [close] from freeing the handle mid-request.
   */
  private fun perform(request: String, action: (Long, ByteArray) -> ByteArray): String =
      synchronized(lock) {
        check(handle != 0L) { "ValhallaActor is closed" }
        decode(action(handle, request.toByteArray()))
      }

  /**
   * The wrapper answers in UTF-8 bytes. A response that is not UTF-8, such as `format: pbf`,
   * becomes the error envelope, byte for byte the answer iOS gives in ValhallaWrapper.mm.
   */
  private fun decode(response: ByteArray): String =
      try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response)).toString()
      } catch (e: CharacterCodingException) {
        "{\"code\":-1,\"message\":\"response was not valid UTF-8\"}"
      }
}
