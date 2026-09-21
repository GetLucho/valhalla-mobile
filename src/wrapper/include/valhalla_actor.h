#ifndef VALHALLAACTOR_H
#define VALHALLAACTOR_H

#include <atomic>
#include <chrono>
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>
#include <valhalla/tyr/actor.h>
#include <valhalla/baldr/tilegetter.h>

class ValhallaMobileHttpClient {
public:
    virtual ~ValhallaMobileHttpClient() = default;
    
    /**
     * Makes a synchronous GET request to fetch tile data
     * @param url the URL to fetch
     * @param range_offset optional offset for range requests
     * @param range_size optional size for range requests
     * @return GET_response_t with the response data and status
     */
    virtual valhalla::baldr::tile_getter_t::GET_response_t 
    get(const std::string& url, uint64_t range_offset = 0, uint64_t range_size = 0) = 0;
    
    /**
     * Makes a synchronous HEAD request to fetch response headers
     * @param url the URL to query
     * @param header_mask mask for which headers to retrieve
     * @return HEAD_response_t with the response headers and status
     */
    virtual valhalla::baldr::tile_getter_t::HEAD_response_t 
    head(const std::string& url, valhalla::baldr::tile_getter_t::header_mask_t header_mask) = 0;

    /**
     * Tells the client whether tiles are fetched gzip-compressed, from
     * `mjolnir.tile_url_gz`.
     *
     * Valhalla inflates tiles itself and decides from that same setting whether to,
     * so the client has to deliver exactly the bytes on the wire: compressed when
     * this is true, uncompressed when it is false. Both platform clients otherwise
     * negotiate an encoding of their own and hand back something inflated, which
     * makes `tile_url_gz: true` fail on every tile.
     *
     * Called once, before the first request. Not pure: a client that only ever
     * serves uncompressed tiles needs no implementation.
     */
    virtual void set_gzipped(bool /*gzipped*/) {}

    /**
     * Whether this client hands back the compressed bytes when asked to.
     *
     * Asking is not the same as being able. NSURLSession decompresses every gzip
     * response transparently and offers no way to opt out -- setting
     * Accept-Encoding yourself does not change it, and the response still reports
     * `Content-Encoding: gzip`, so the body looks compressed by every header and
     * is not. Valhalla then inflates an already-inflated tile, DecompressTile
     * returns null, and CacheTileURL dereferences it: the process dies with
     * SIGSEGV rather than failing the fetch.
     *
     * So the wrapper asks the client what it actually delivers and reports THAT
     * to valhalla, instead of reporting what the config asked for. A client that
     * cannot keep bytes compressed makes `tile_url_gz: true` behave as false --
     * the tiles still cross the wire compressed, because the platform negotiated
     * that itself; they are simply stored uncompressed.
     */
    virtual bool delivers_compressed_bytes() const {
        return false;
    }
};

/**
 * Owns one Valhalla actor and runs actions against it.
 *
 * Every action runs on a dedicated thread with a 16 MB stack, joined before the call
 * returns: Valhalla's map matcher recurses once per matched edge, which overflows the
 * ~1 MB stack a mobile worker thread has on a long trace. The recursion is unbounded, so
 * this raises the ceiling rather than removing it. Actions stay synchronous, and anything
 * Valhalla calls back out of an action runs on that thread too — see valhalla_actor.cpp.
 *
 * The actor is not safe to use from several threads at once, and this class does not make
 * it so; the Kotlin and Obj-C wrappers serialize calls.
 */
class ValhallaActor {
private:
    std::unique_ptr<valhalla::tyr::actor_t> actor;
    std::unique_ptr<valhalla::baldr::GraphReader> graph_reader;

    /// Seconds an action may spend fetching tiles, or 0 for no limit.
    std::atomic<double> tile_fetch_timeout_seconds{0.0};
    /// When the action running now must stop fetching. Steady, so a clock change cannot
    /// move it. Set at the start of every action and read from the fetching thread.
    std::atomic<std::chrono::steady_clock::rep> fetch_deadline{0};
    /// Installed on the GraphReader once, and held here because it stores the pointer.
    std::function<void()> interrupt;
    /// Set by the interrupt when it fires, cleared when an action is armed.
    ///
    /// The exception the interrupt throws does not reach the caller: somewhere in the fetch
    /// path valhalla catches it, and loki then reports "No suitable edges near location"
    /// (error 171) -- the same answer a genuinely unroutable address gives. Recording that
    /// the deadline fired is how an action that gave up is told apart from one that looked
    /// and found nothing, without archaeology through internals that are not ours.
    std::atomic<bool> deadline_fired{false};
    /// Set by [cancel], cleared by [resume]. Read from the fetching thread.
    std::atomic<bool> owned_cancelled{false};
    /// Either &owned_cancelled or the caller's flag. Never null after construction.
    std::atomic<bool>* cancelled = &owned_cancelled;

    /// Arm the deadline for an action about to run, then run it.
    std::string with_deadline(const std::function<std::string()>& action);

    /// Arm the deadline without running anything, for callers that are not string actions.
    void arm_deadline();

    /// Why the action gave up, for the exception message.
    std::string deadline_message() const;

public:
    /**
     * @param cancel_flag  optional, and NOT owned. When given, [cancel] and [resume] set it
     *                     and the interrupt reads it, so a caller can stop a running action
     *                     without touching this object -- which matters because every other
     *                     method holds a lock for its duration, and an actor being freed
     *                     concurrently would otherwise leave cancel reading a dangling
     *                     pointer. It must outlive this actor.
     */
    ValhallaActor(const std::string& config_path,
                  ValhallaMobileHttpClient* http_client = nullptr,
                  std::atomic<bool>* cancel_flag = nullptr);

    /// Raised when an action gave up because [set_tile_fetch_timeout_seconds] elapsed.
    ///
    /// A distinct type so a caller can tell "the origin is slow or gone" from "this route
    /// does not exist", which otherwise both surface as a generic failure.
    class TimedOut : public std::runtime_error {
    public:
        explicit TimedOut(const std::string& what) : std::runtime_error(what) {}
    };

    /// One tile of the hierarchy: what to ask the CDN for, and what to fetch.
    struct TileRef {
        /// Hierarchy level. 0 is 4 degrees, 1 is 1 degree, 2 is 0.25 degrees.
        uint32_t level = 0;
        /// Tile id within that level.
        uint32_t id = 0;
        /// The path `mjolnir.tile_url`'s {tilePath} is replaced with, e.g. "2/000/818/660.gph".
        ///
        /// Always the uncompressed name. With `tile_url_gz` on the CACHED file is .gph.gz,
        /// but the URL is unchanged -- CacheTileURL builds the fetch name from the plain
        /// suffix, so the two differ deliberately.
        std::string path;
    };

    /**
     * The tiles covering one coordinate, one per hierarchy level.
     *
     * From `TileHierarchy::levels()` and `GraphTile::FileSuffix`, which is the point: three
     * hand-maintained ports of this arithmetic had already drifted twice -- a NaN guard wrong
     * in exactly one of them, and a bounds rule that dropped the poles in two. Reading it from
     * the engine that defines it makes drift impossible by construction rather than by three
     * test suites kept in step by eye.
     *
     * The path carries the suffix the CONFIG asks for, so it matches what the cache stores.
     */
    std::vector<TileRef> tiles_covering(double latitude, double longitude) const;

    /**
     * Ensure one tile is in `mjolnir.tile_dir`, fetching it if it is not.
     *
     * This is the prefetch, and it deliberately does no downloading of its own: it calls
     * `GraphReader::GetGraphTile`, so a prefetched tile arrives through exactly the path a
     * route would have used -- same cache, same naming, same gzip handling, same id.txt and
     * rebuild detection. A second downloader would be a second set of all of those.
     *
     * @return true when the tile is now cached, false when the origin does not have it.
     *
     * A false is normal and is not an error: two of the sixteen level-2 tiles over Lake and
     * Porter counties are Lake Michigan. A prefetch that treated a miss as failure could not
     * prepare any coastal or border region.
     */
    bool ensure_tile_cached(uint32_t level, uint32_t id);

    /// Raised when an action stopped because [cancel] was called.
    class Cancelled : public std::runtime_error {
    public:
        explicit Cancelled(const std::string& what) : std::runtime_error(what) {}
    };

    /**
     * Ask the action running now to stop at its next tile fetch.
     *
     * Checked in the same place as the deadline, so the granularity is one request: a fetch
     * already in flight finishes or hits its own timeout. Sticky until [resume] clears it,
     * because a cancel that raced ahead of the action it meant to stop would be ignored.
     */
    void cancel();

    /// Clear a previous [cancel] so further actions can run.
    void resume();

    /**
     * Bound how long an action may spend fetching tiles. 0, the default, is no limit.
     *
     * Normally set from `mjolnir.tile_url_timeout` in the config rather than called; this
     * exists for a caller that needs to change it after construction.
     *
     * This is not the same as an HTTP timeout and does not replace one. A platform client
     * caps a single request; one route attempts tile after tile, each paying its own
     * timeout in turn, so the operation is unbounded even when every request is bounded.
     * Measured against a dead origin on an iOS simulator with a 10 s per-request cap: 170
     * seconds, during which the app looks frozen.
     *
     * Checked between tile fetches, so the granularity is one request. A fetch already in
     * flight when the deadline passes is not cancelled -- it finishes or hits its own
     * timeout, and the next one throws. Connect and DNS stalls are the platform client's
     * business; this bounds how many of them an action can accumulate.
     */
    void set_tile_fetch_timeout_seconds(double seconds);

    /**
     * Compute a route between the given locations. This is Valhalla's `route`
     * action.
     *
     * @param request  a `route` request as JSON. See
     *                 https://valhalla.github.io/valhalla/api/turn-by-turn/api-reference/
     * @return         the serialized response, in whichever format the request asked for
     */
    std::string route(const std::string& request);

    /**
     * Map-match a GPS trace onto the road network and return a route along the
     * matched path. This is Valhalla's `trace_route` action.
     *
     * @param request  a `trace_route` request as JSON. See
     *                 https://valhalla.github.io/valhalla/api/map-matching/api-reference/
     * @return         the serialized response, in whichever format the request asked for
     */
    std::string trace_route(const std::string& request);

    /**
     * Map-match a GPS trace onto the road network and return the attributes of
     * every edge along the matched path. This is Valhalla's `trace_attributes`
     * action.
     *
     * Unlike `trace_route`, this action always answers with Valhalla's own JSON —
     * the `format` option does not apply to it.
     *
     * @param request  a `trace_attributes` request as JSON. See
     *                 https://valhalla.github.io/valhalla/api/map-matching/api-reference/
     * @return         the serialized JSON response
     */
    std::string trace_attributes(const std::string& request);

    /**
     * Sample terrain heights under a shape. This is Valhalla's `height` action.
     *
     * @param request  a `height` request as JSON. See
     *                 https://valhalla.github.io/valhalla/api/elevation/api-reference/
     * @return         the serialized JSON response
     */
    std::string height(const std::string& request);

    /**
     * Compute a matrix of costs and times between every source and every target. This is
     * Valhalla's `sources_to_targets` action.
     *
     * @param request  a `sources_to_targets` request as JSON. See
     *                 https://valhalla.github.io/valhalla/api/matrix/api-reference/
     * @return         the serialized response, in whichever format the request asked for
     */
    std::string matrix(const std::string& request);
};

#endif // VALHALLAACTOR_H
