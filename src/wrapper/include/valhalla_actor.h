#ifndef VALHALLAACTOR_H
#define VALHALLAACTOR_H

#include <atomic>
#include <chrono>
#include <functional>
#include <stdexcept>
#include <string>
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

    /// Arm the deadline for an action about to run, then run it.
    std::string with_deadline(const std::function<std::string()>& action);
public:
    ValhallaActor(const std::string& config_path, ValhallaMobileHttpClient* http_client = nullptr);

    /// Raised when an action gave up because [set_tile_fetch_timeout_seconds] elapsed.
    ///
    /// A distinct type so a caller can tell "the origin is slow or gone" from "this route
    /// does not exist", which otherwise both surface as a generic failure.
    class TimedOut : public std::runtime_error {
    public:
        explicit TimedOut(const std::string& what) : std::runtime_error(what) {}
    };

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
