#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <string>
#include <system_error>
#include <type_traits>
#include <utility>
#include <vector>

#include <cmath>
#include <pthread.h>
#include <zlib.h>

#include <boost/property_tree/ptree.hpp>
#include <valhalla/tyr/actor.h>
#include <valhalla/baldr/compression_utils.h>
#include <valhalla/baldr/graphtileheader.h>
#include <valhalla/baldr/rapidjson_utils.h>
#include <valhalla/baldr/graphid.h>
#include <valhalla/baldr/graphtile.h>
#include <valhalla/baldr/tilehierarchy.h>
#include <valhalla/loki/worker.h>
#include <valhalla/midgard/pointll.h>
#include "valhalla_actor.h"

namespace {

/// zlib level for tiles that arrive uncompressed.
/// Level 1 is the fastest:
/// on a 37 MB tile (Apple M4) it took 275 ms for 39.5% of the raw size,
/// against 1.2 s for 35.5% at level 6.
constexpr int kTileGzipLevel = 1;

constexpr size_t kTileHeaderSize = sizeof(valhalla::baldr::GraphTileHeader);

/// A raw tile can't start with the gzip magic:
/// its first byte holds the hierarchy level in the low 3 bits,
/// and 0x1f would mean level 7.
bool has_gzip_magic(const char* bytes, size_t size) {
    return size >= 2 && static_cast<unsigned char>(bytes[0]) == 0x1f &&
           static_cast<unsigned char>(bytes[1]) == 0x8b;
}

/// The size a tile's header records for the whole tile.
/// Valhalla refuses a tile whose size doesn't match it exactly.
uint64_t recorded_tile_size(const char* header) {
    valhalla::baldr::GraphTileHeader parsed;
    std::memcpy(&parsed, header, sizeof(parsed));
    return parsed.end_offset();
}

/// Whether a raw body is one whole tile.
bool is_raw_tile(const std::vector<char>& bytes) {
    return bytes.size() >= kTileHeaderSize && recorded_tile_size(bytes.data()) == bytes.size();
}

/// Frees a zlib inflate stream on every way out, including an allocation that throws.
struct InflateEnd {
    z_stream& stream;
    ~InflateEnd() {
        inflateEnd(&stream);
    }
};

/**
 * Inflates a gzip body and checks that it holds exactly one whole tile.
 *
 * The whole stream is read, not just the header,
 * so a truncated or corrupt tail fails here,
 * instead of in valhalla, which dereferences the null that DecompressTile returns.
 * zlib checks the CRC and length in the gzip trailer.
 *
 * @param plain  receives the tile when given; otherwise the output is discarded.
 */
bool inflate_tile(const std::vector<char>& gzip, std::vector<char>* plain) {
    if (gzip.size() > std::numeric_limits<uInt>::max()) {
        return false;
    }
    z_stream stream{};
    if (inflateInit2(&stream, MAX_WBITS + 16) != Z_OK) {
        return false;
    }
    InflateEnd end{stream};
    stream.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(gzip.data()));
    stream.avail_in = static_cast<uInt>(gzip.size());

    // The header first, because it records how big the whole tile is.
    char header[kTileHeaderSize];
    stream.next_out = reinterpret_cast<Bytef*>(header);
    stream.avail_out = sizeof(header);
    int status = Z_OK;
    while (stream.avail_out > 0 && status == Z_OK) {
        status = ::inflate(&stream, Z_NO_FLUSH);
    }
    // A second gzip layer would pass valhalla's inflate and then fail as a tile.
    if (stream.avail_out > 0 || has_gzip_magic(header, sizeof(header))) {
        return false;
    }
    const uint64_t size = recorded_tile_size(header);
    if (size < kTileHeaderSize || size > std::numeric_limits<uInt>::max()) {
        return false;
    }

    if (plain) {
        plain->resize(size);
        std::memcpy(plain->data(), header, kTileHeaderSize);
        stream.next_out = reinterpret_cast<Bytef*>(plain->data()) + kTileHeaderSize;
        stream.avail_out = static_cast<uInt>(size - kTileHeaderSize);
        if (status == Z_OK) {
            status = ::inflate(&stream, Z_FINISH);
        }
    } else {
        std::vector<char> scratch(64 * 1024);
        while (status == Z_OK) {
            stream.next_out = reinterpret_cast<Bytef*>(scratch.data());
            stream.avail_out = static_cast<uInt>(scratch.size());
            status = ::inflate(&stream, Z_NO_FLUSH);
        }
    }
    // Anything after the gzip trailer is not part of the tile.
    return status == Z_STREAM_END && stream.avail_in == 0 && stream.total_out == size;
}

/// Gzips `plain` into `out`, using valhalla's deflate, which cleans up if an allocation throws.
bool gzip_tile(const std::vector<char>& plain, std::vector<char>& out) {
    if (plain.size() > std::numeric_limits<uInt>::max()) {
        return false;
    }
    out.clear();
    auto src = [&plain](z_stream& s) -> int {
        s.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(plain.data()));
        s.avail_in = static_cast<uInt>(plain.size());
        return Z_FINISH;
    };
    // Called when the output is full, and once more at the end to trim it.
    // Tiles usually compress to under half their size.
    auto dst = [&plain, &out](z_stream& s) {
        const size_t size = out.size();
        if (s.total_out < size) {
            out.resize(s.total_out);
            return;
        }
        const size_t step = std::max<size_t>(plain.size() / 2, 64 * 1024);
        out.resize(size + step);
        s.next_out = reinterpret_cast<Bytef*>(out.data() + size);
        s.avail_out = static_cast<uInt>(step);
    };
    return valhalla::baldr::deflate(src, dst, kTileGzipLevel, true);
}

} // namespace

class TileGetterWrapper : public valhalla::baldr::tile_getter_t {
public:
  /**
   * Honour the interrupt GraphReader installs.
   *
   * tile_getter_t::set_interrupt has an EMPTY default body, so a getter that does not
   * override it makes GraphReader::SetInterrupt silently do nothing -- the call succeeds,
   * the callback is stored, and it is never invoked. That is what this class did.
   *
   * It matters because a per-request HTTP timeout does not bound the operation. Both
   * platform clients already cap a single request at 10 s, and a route against a dead
   * origin still took 170 seconds measured on an iOS simulator: one route attempts tile
   * after tile, each paying its own timeout in turn. Bounding the whole operation needs a
   * check between fetches, which is exactly what this is.
   *
   * The interrupt is a std::function<void()> that THROWS to abort; see
   * curl_tilegetter.h, where the same pointer reaches libcurl's progress callback.
   */
  void set_interrupt(const interrupt_t* interrupt) override {
    interrupt_ = interrupt;
  }

  /**
   * @param http_client  client used to perform HTTP GET/HEAD tile requests;
   *                      ownership is transferred to the wrapper. May be null,
   *                      in which case requests report FAILURE.
   * @param is_gzipped  whether valhalla stores tiles gzip-compressed
   */
  TileGetterWrapper(std::unique_ptr<ValhallaMobileHttpClient> http_client, bool is_gzipped): http_client(std::move(http_client)), is_gzipped(is_gzipped) {
  }

  GET_response_t get(const std::string& url,
                     const uint64_t range_offset = 0,
                     const uint64_t range_size = 0) override {
    // Before the request, not after: the point is to stop paying for fetches once the
    // caller has given up, and a check that runs only afterwards still pays for this one.
    if (interrupt_) {
      (*interrupt_)();
    }
    GET_response_t result;
    if (!http_client) {
      result.status_ = tile_getter_t::status_code_t::FAILURE;
      return result;
    }
    // Ranges are slices of a remote tar and are passed through as is,
    // as long as they are exactly the slice asked for:
    // a server that ignores Range sends the whole tar instead.
    if (range_size > 0) {
      result = http_client->get(url, range_offset, range_size, false);
      if (result.status_ == tile_getter_t::status_code_t::SUCCESS &&
          result.bytes_.size() != range_size) {
        printf("[ValhallaActor] range of %s returned %zu bytes, not %llu\n", url.c_str(),
               result.bytes_.size(), static_cast<unsigned long long>(range_size));
        result.status_ = tile_getter_t::status_code_t::FAILURE;
      }
      return result;
    }
    result = http_client->get(url, 0, 0, is_gzipped);
    if (result.status_ == tile_getter_t::status_code_t::SUCCESS &&
        !to_stored_form(url, result.bytes_)) {
      result.status_ = tile_getter_t::status_code_t::FAILURE;
    }
    return result;
  }

  HEAD_response_t head(const std::string& url, header_mask_t header_mask) override {
    if (interrupt_) {
      (*interrupt_)();
    }
    HEAD_response_t result;
    if (http_client) { 
        result = http_client->head(url, header_mask);
    } else {
        result.status_ = tile_getter_t::status_code_t::FAILURE;
    }
    return result;
  }

  bool gzipped() const override {
    return is_gzipped;
  }

private:
  /**
   * Checks a downloaded tile and converts it to the form valhalla stores:
   * gzip when is_gzipped, raw otherwise.
   * Valhalla caches whatever it is handed,
   * so a body that isn't one whole tile has to be rejected here,
   * or it fails on every later load.
   */
  bool to_stored_form(const std::string& url, std::vector<char>& bytes) const {
    const char* problem = nullptr;
    if (has_gzip_magic(bytes.data(), bytes.size())) {
      // The server sent gzip and the client kept it, or the server hosts .gz files.
      std::vector<char> plain;
      if (!inflate_tile(bytes, is_gzipped ? nullptr : &plain)) {
        problem = "is not one whole gzip-compressed tile";
      } else if (!is_gzipped) {
        bytes.swap(plain);
      }
    } else if (!is_raw_tile(bytes)) {
      problem = "is not one whole tile";
    } else if (is_gzipped) {
      std::vector<char> gzip;
      if (gzip_tile(bytes, gzip)) {
        bytes.swap(gzip);
      } else {
        problem = "could not be gzip-compressed";
      }
    }
    if (problem) {
      printf("[ValhallaActor] tile %s %s\n", url.c_str(), problem);
    }
    return problem == nullptr;
  }

  bool is_gzipped;
  std::unique_ptr<ValhallaMobileHttpClient> http_client;
  // Owned by GraphReader, which outlives this getter.
  const interrupt_t* interrupt_ = nullptr;
};


namespace {

// Valhalla's edge-walking map matcher is recursive: `expand_from_node` in
// src/valhalla/src/thor/route_matcher.cc calls itself once per matched edge, and once
// more per hierarchy transition, with nothing bounding the depth.
// A long shape therefore needs a deep stack.
// Tracing a ~1,300 edge route overflows the ~1 MB an Android JNI worker thread or a Dart
// isolate worker gets, and the 512 KB a pthread created with default attributes gets on
// Darwin, while the identical request succeeds on the 8 MB main thread.
// Server deployments never meet this because their worker threads already start at 8 MB.
// See https://github.com/Rallista/valhalla-mobile/issues/89.
//
// 16 MB is a reservation of address space, not of memory: pages are committed only as the
// stack actually grows, and the thread is destroyed when the action returns, so nothing is
// held between calls.
constexpr size_t kActorStackSize = 16 * 1024 * 1024;

// One action to run, plus the two ways it can end.
// The action is held by reference: it outlives the thread, which is joined before
// run_on_deep_stack returns.
template <typename Action> struct ActionCall {
    Action& action;
    std::string result;
    std::exception_ptr error;
};

// The pthread entry point.
// Nothing may escape it — an exception unwinding out of a thread start routine terminates
// the process — so it is captured here and rethrown on the caller's thread.
template <typename Action> void* run_action(void* arg) {
    auto* call = static_cast<ActionCall<Action>*>(arg);
    try {
        call->result = call->action();
    } catch (...) {
        call->error = std::current_exception();
    }
    return nullptr;
}

/**
 * Runs one actor action on a thread with a 16 MB stack, joining it before returning.
 *
 * The platform entry points stay where they are: every JNIEnv use in main.cpp's JNI functions,
 * and every Obj-C++ call in ValhallaWrapper.mm, still runs on the thread the platform called in
 * on. What moves with the action is whatever Valhalla calls back out of it, which is the tile
 * getter. On Android that is JniHttpClient, which reaches the JVM from this thread, so ScopedEnv
 * attaches and detaches around each fetch; on Apple the getter brings its own @autoreleasepool,
 * so this thread needs none. The call remains synchronous, and callers are serialized one layer
 * up (`synchronized` in Kotlin, `@synchronized` in Obj-C), so the actor is still only ever
 * touched by one thread at a time.
 *
 * Anything the action throws is rethrown on the caller's thread, where the platform
 * boundary turns it into the error envelope exactly as before.
 */
template <typename Action> std::string run_on_deep_stack(Action&& action) {
    using Call = ActionCall<std::remove_reference_t<Action>>;
    // On the heap because a join that fails would leave the thread possibly still writing
    // into it; see below.
    auto call = std::unique_ptr<Call>(new Call{action, {}, nullptr});

    pthread_attr_t attr;
    int rc = pthread_attr_init(&attr);
    if (rc != 0) {
        throw std::system_error(rc, std::generic_category(), "pthread_attr_init");
    }
    rc = pthread_attr_setstacksize(&attr, kActorStackSize);
    if (rc != 0) {
        pthread_attr_destroy(&attr);
        throw std::system_error(rc, std::generic_category(), "pthread_attr_setstacksize");
    }

    pthread_t thread;
    rc = pthread_create(&thread, &attr, &run_action<std::remove_reference_t<Action>>,
                        call.get());
    pthread_attr_destroy(&attr);
    if (rc != 0) {
        // EAGAIN under memory pressure is the realistic case, and it reaches the caller as
        // the error envelope rather than as a crash.
        throw std::system_error(rc, std::generic_category(), "pthread_create");
    }

    rc = pthread_join(thread, nullptr);
    if (rc != 0) {
        // Unreachable through this path — the handle is valid, joinable, and not this
        // thread — but were it ever to happen the thread could still hold a pointer to the
        // call, so the call is deliberately leaked rather than freed underneath it.
        (void)call.release();
        throw std::system_error(rc, std::generic_category(), "pthread_join");
    }

    if (call->error) {
        std::rethrow_exception(call->error);
    }
    return std::move(call->result);
}

} // namespace

ValhallaActor::ValhallaActor(const std::string& config_path,
                             ValhallaMobileHttpClient* http_client,
                             std::atomic<bool>* cancel_flag) {
    if (cancel_flag != nullptr) {
      cancelled = cancel_flag;
    }
    // Take ownership of the client immediately so it is freed on any early
    // return or exception below, and regardless of whether a getter is attached.
    std::unique_ptr<ValhallaMobileHttpClient> http_client_owned(http_client);

    std::string config_file(config_path);

    // Set up the config object
    boost::property_tree::ptree config;
    rapidjson::read_json(config_file, config);

    auto mjolnir_config = config.get_child("mjolnir");
    // Only attach the HTTP tile-getter when a tile_url is configured. Passing a
    // getter unconditionally forces GraphReader into fetch mode, so in pure
    // loose-tile mode (tile_dir set, tile_url empty) a referenced-but-missing
    // tile attempts a remote fetch against an empty URL and throws
    // (std::exception: basic_string) instead of returning nullptr. With a null
    // getter, GraphReader::GetGraphTile returns nullptr for a missing loose tile
    // (`if (!tile_getter_) return nullptr;`) — matching upstream Valhalla, so the
    // router routes around the gap. This is what offline tile_dir consumers
    // expect (e.g. region packs that don't bundle the full tile hierarchy).
    // When no tile_url is set, http_client_owned is left to free the client at
    // scope exit (loose-tile mode needs no getter).
    std::unique_ptr<TileGetterWrapper> tile_getter;
    const auto tile_url = mjolnir_config.get<std::string>("tile_url", std::string());
    if (!tile_url.empty()) {
      // A remote tar is read in byte ranges that are passed through as is,
      // so gzip only applies to URLs that name each tile.
      // Without a tile_dir nothing is stored,
      // so compressing would only be undone straight away.
      const bool gzipped =
          mjolnir_config.get<bool>("tile_url_gz", false) &&
          tile_url.find(valhalla::baldr::GraphTile::kTilePathPattern) != std::string::npos &&
          !mjolnir_config.get<std::string>("tile_dir", std::string()).empty();
      tile_getter = std::make_unique<TileGetterWrapper>(std::move(http_client_owned), gzipped);
    }
    graph_reader = std::make_unique<valhalla::baldr::GraphReader>(
      mjolnir_config, std::move(tile_getter)
    );
    // From the config, not from an API call. `mjolnir.tile_url_timeout` is the name
    // upstream would use if this were upstream -- curler_t's constructor already takes
    // config-derived strings, so the shape exists -- which keeps a future patch honest and
    // means a consumer configures the engine in one place instead of two. Seconds, and
    // absent or 0 means no limit, matching every other optional mjolnir key.
    set_tile_fetch_timeout_seconds(mjolnir_config.get<double>("tile_url_timeout", 0.0));

    // Installed once, and it must outlive the reader: GraphReader stores the POINTER,
    // it does not copy the function.
    //
    // Throwing is how the interrupt aborts -- curl_tilegetter's progress callback catches
    // and returns -1 for exactly this, and TileGetterWrapper::get lets it propagate.
    interrupt = [this]() {
      // Cancellation first: a user who pressed stop should not wait out the deadline.
      if (cancelled->load(std::memory_order_relaxed)) {
        deadline_fired.store(true, std::memory_order_relaxed);
        throw Cancelled(kCancelledMessage);
      }
      const auto limit = fetch_deadline.load(std::memory_order_relaxed);
      if (limit == 0) {
        return;
      }
      if (std::chrono::steady_clock::now().time_since_epoch().count() > limit) {
        deadline_fired.store(true, std::memory_order_relaxed);
        throw TimedOut(kTimedOutMessage);
      }
    };
    // Also set here, for any path that reaches the reader without going through a worker.
    // It is NOT enough on its own: loki_worker_t::set_interrupt and thor_worker_t::set_interrupt
    // both call reader->SetInterrupt(interrupt), and every action calls them with whatever that
    // action was given -- which is nullptr unless one is passed. So an interrupt installed once
    // at construction is overwritten with null by the first action that runs, which is why each
    // action below passes it explicitly.
    graph_reader->SetInterrupt(&interrupt);

    // Setup the actor
    actor = std::make_unique<valhalla::tyr::actor_t>(config, *graph_reader, true);
}

void ValhallaActor::cancel() {
    cancelled->store(true, std::memory_order_relaxed);
}

void ValhallaActor::resume() {
    cancelled->store(false, std::memory_order_relaxed);
}

std::vector<ValhallaActor::TileRef> ValhallaActor::tiles_covering(double latitude,
                                                                 double longitude) const {
    std::vector<TileRef> covering;
    // A coordinate off the planet has no tiles rather than a wrong one. PointLL would
    // happily construct and TileId would return an index into nothing.
    if (!std::isfinite(latitude) || !std::isfinite(longitude) || latitude < -90.0 ||
        latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
      return covering;
    }

    const valhalla::midgard::PointLL point{longitude, latitude};
    // What a tile is called when REQUESTED, which is not what it is called on disk.
    // With tile_url_gz on, GraphTile::store() writes .gph.gz (graphtile.cc:211-218), but
    // CacheTileURL builds the fetch name from the plain suffix -- the URL does not change.
    // So this is always the uncompressed name, and the cached name is valhalla's business.
    const std::string suffix = valhalla::baldr::SUFFIX_NON_COMPRESSED;
    for (const auto& level : valhalla::baldr::TileHierarchy::levels()) {
      TileRef ref;
      ref.level = level.level;
      ref.id = static_cast<uint32_t>(level.tiles.TileId(point));
      ref.path = valhalla::baldr::GraphTile::FileSuffix(
          valhalla::baldr::GraphId(ref.id, ref.level, 0), suffix, &level);
      covering.push_back(ref);
    }
    return covering;
}

bool ValhallaActor::ensure_tile_cached(uint32_t level, uint32_t id) {
    // Armed like any other action, so the deadline and cancel both apply. No deep stack:
    // that exists for the map matcher's unbounded recursion, and this is one fetch.
    arm_deadline();
    // GetGraphTile, not a download of our own: a prefetched tile arrives through exactly
    // the path a route would have used, so there is one cache, one naming rule, one gzip
    // decision and one rebuild check rather than two of each.
    const valhalla::baldr::GraphId graphid(id, level, 0);
    const bool cached = static_cast<bool>(graph_reader->GetGraphTile(graphid));
    // Same reason as with_deadline: the interrupt throws and valhalla swallows it, so a tile
    // abandoned on the deadline would otherwise look identical to one the origin does not
    // have -- and for a prefetch those mean opposite things.
    if (deadline_fired.load(std::memory_order_relaxed)) {
        throw TimedOut(deadline_message());
    }
    return cached;
}

void ValhallaActor::set_tile_fetch_timeout_seconds(double seconds) {
    tile_fetch_timeout_seconds.store(seconds < 0 ? 0 : seconds, std::memory_order_relaxed);
}

std::string ValhallaActor::with_deadline(const std::function<std::string()>& action) {
    arm_deadline();
    // Checked after, not relied on to propagate. The interrupt throws, valhalla catches it
    // somewhere in the fetch path, and loki answers 171 "No suitable edges near location" --
    // indistinguishable from a genuinely unroutable address. A caller needs to tell "the
    // origin is gone" from "this route does not exist", so the flag is what says so.
    std::string answer;
    try {
        answer = action();
    } catch (...) {
        if (deadline_fired.load(std::memory_order_relaxed)) {
            throw TimedOut(deadline_message());
        }
        throw;
    }
    if (deadline_fired.load(std::memory_order_relaxed)) {
        throw TimedOut(deadline_message());
    }
    return answer;
}

std::string ValhallaActor::deadline_message() const {
    // Fixed strings, and no numbers in them. A caller has to tell these two apart from a
    // genuine routing failure, and the only signal that survives the trip to Swift and Kotlin
    // is the text -- so it has to be stable, and std::to_string(double) writes "15.000000".
    // The caller configured the timeout, so it already knows the number.
    if (cancelled->load(std::memory_order_relaxed)) {
        return kCancelledMessage;
    }
    return kTimedOutMessage;
}

void ValhallaActor::arm_deadline() {
    deadline_fired.store(false, std::memory_order_relaxed);
    const auto seconds = tile_fetch_timeout_seconds.load(std::memory_order_relaxed);
    if (seconds <= 0) {
        fetch_deadline.store(0, std::memory_order_relaxed);
    } else {
        // Armed per action, not per construction: the budget is "this route may spend N
        // seconds", not "this engine may spend N seconds in its lifetime".
        const auto now = std::chrono::steady_clock::now().time_since_epoch();
        const auto budget = std::chrono::duration_cast<std::chrono::steady_clock::duration>(
            std::chrono::duration<double>(seconds));
        fetch_deadline.store((now + budget).count(), std::memory_order_relaxed);
    }
}

std::string ValhallaActor::route(const std::string& request) {
    return with_deadline([&]() {
        return run_on_deep_stack([&]() { return actor->route(request, &interrupt); });
    });
}

std::string ValhallaActor::trace_route(const std::string& request) {
    return with_deadline([&]() {
        return run_on_deep_stack([&]() { return actor->trace_route(request, &interrupt); });
    });
}

std::string ValhallaActor::trace_attributes(const std::string& request) {
    return with_deadline([&]() {
        return run_on_deep_stack([&]() { return actor->trace_attributes(request, &interrupt); });
    });
}

std::string ValhallaActor::height(const std::string& request) {
    return with_deadline([&]() {
        return run_on_deep_stack([&]() { return actor->height(request, &interrupt); });
    });
}

std::string ValhallaActor::matrix(const std::string& request) {
    return with_deadline([&]() {
        return run_on_deep_stack([&]() { return actor->matrix(request, &interrupt); });
    });
}
