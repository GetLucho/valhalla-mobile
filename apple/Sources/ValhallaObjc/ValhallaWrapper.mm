#include <atomic>

#import "ValhallaWrapper.h"

#import <include/main.h>
#import <Foundation/Foundation.h>

namespace {

/**
 * Runs one request to completion and hands back what it produced.
 *
 * Valhalla's tile getter is synchronous — it asks for a tile and expects the bytes back — so the
 * asynchronous session API is bridged with a semaphore rather than pushed up into the caller.
 *
 * Waiting here cannot deadlock: NSURLSession runs a completion handler on its own delegate queue,
 * never on the thread that resumed the task, so the thread being blocked is not the thread the
 * completion needs. It does block, though, which is why a routing action against a config with a
 * tile URL does not belong on the main thread.
 *
 * `sendSynchronousRequest:returningResponse:error:` did this before, and has been deprecated since
 * iOS 9.
 *
 * @param request       the request to run.
 * @param outResponse   set to the HTTP response, or nil if the request never got one.
 * @param outError      set to the transport error, if there was one.
 * @param timeout_seconds  how long the whole request may take before it's cancelled, or 0.
 * @return              the response body, or nil.
 */
NSData* PerformSynchronously(NSURLRequest* request,
                             NSHTTPURLResponse* __strong * outResponse,
                             NSError* __strong * outError,
                             double timeout_seconds = 0) {
    __block NSData* data = nil;
    __block NSURLResponse* response = nil;
    __block NSError* error = nil;

    dispatch_semaphore_t finished = dispatch_semaphore_create(0);

    NSURLSessionDataTask* task =
        [[NSURLSession sharedSession] dataTaskWithRequest:request
                                       completionHandler:^(NSData* taskData,
                                                           NSURLResponse* taskResponse,
                                                           NSError* taskError) {
            data = taskData;
            response = taskResponse;
            error = taskError;
            dispatch_semaphore_signal(finished);
        }];
    [task resume];
    // timeoutInterval only bounds the gap between packets, so a trickling body needs a cap here.
    const dispatch_time_t limit =
        timeout_seconds > 0
            ? dispatch_time(DISPATCH_TIME_NOW, static_cast<int64_t>(timeout_seconds * NSEC_PER_SEC))
            : DISPATCH_TIME_FOREVER;
    if (dispatch_semaphore_wait(finished, limit) != 0) {
        [task cancel];
        dispatch_semaphore_wait(finished, DISPATCH_TIME_FOREVER);
    }

    // A non-HTTP response cannot carry a status code, so it is treated as no response at all.
    *outResponse = [response isKindOfClass:[NSHTTPURLResponse class]]
                       ? (NSHTTPURLResponse*)response
                       : nil;
    *outError = error;

    return data;
}

} // namespace

/**
 * iOS implementation of ValhallaMobileHttpClient using NSMutableURLRequest
 */
class ValhallaMobileHttpClientImpl : public ValhallaMobileHttpClient {
public:
    /// NSURLSession always decompresses, so accept_gzip is ignored; the tile getter recompresses.
    valhalla::baldr::tile_getter_t::GET_response_t
    get(const std::string& url, uint64_t range_offset, uint64_t range_size,
        bool /*accept_gzip*/, double timeout_seconds) override {
        valhalla::baldr::tile_getter_t::GET_response_t response;
        
        @autoreleasepool {
            NSString* urlString = [NSString stringWithUTF8String:url.c_str()];
            NSURL* nsurl = [NSURL URLWithString:urlString];
            
            if (!nsurl) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = 0;
                return response;
            }
            
            NSMutableURLRequest* request = [NSMutableURLRequest requestWithURL:nsurl];
            request.HTTPMethod = @"GET";
            request.timeoutInterval = 10;

            // Set range header if needed
            if (range_size > 0) {
                NSString* rangeHeader = [NSString stringWithFormat:@"bytes=%llu-%llu", 
                                                  range_offset, range_offset + range_size - 1];
                [request setValue:rangeHeader forHTTPHeaderField:@"Range"];
                // Keep a slice of a tar uncompressed.
                [request setValue:@"identity" forHTTPHeaderField:@"Accept-Encoding"];
            }
            
            NSHTTPURLResponse* httpResponse = nil;
            NSError* error = nil;
            
            NSData* data = PerformSynchronously(request, &httpResponse, &error, timeout_seconds);
            
            if (error || !httpResponse) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = httpResponse ? httpResponse.statusCode : 0;
                return response;
            }
            
            response.http_code_ = httpResponse.statusCode;

            if (httpResponse.statusCode >= 200 && httpResponse.statusCode < 300) {
                // Copy data to response bytes
                if (data) {
                    const char* dataBytes = static_cast<const char*>(data.bytes);
                    response.bytes_.assign(dataBytes, dataBytes + data.length);
                }
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::SUCCESS;
            } else {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
            }
        }
        
        return response;
    }
    
    valhalla::baldr::tile_getter_t::HEAD_response_t 
    head(const std::string& url, valhalla::baldr::tile_getter_t::header_mask_t header_mask) override {
        valhalla::baldr::tile_getter_t::HEAD_response_t response;
        
        @autoreleasepool {
            NSString* urlString = [NSString stringWithUTF8String:url.c_str()];
            NSURL* nsurl = [NSURL URLWithString:urlString];
            
            if (!nsurl) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = 0;
                return response;
            }
            
            NSMutableURLRequest* request = [NSMutableURLRequest requestWithURL:nsurl];
            request.HTTPMethod = @"HEAD";
            request.timeoutInterval = 10;
            
            NSHTTPURLResponse* httpResponse = nil;
            NSError* error = nil;
            
            PerformSynchronously(request, &httpResponse, &error);
            
            if (error || !httpResponse) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
                response.http_code_ = httpResponse ? httpResponse.statusCode : 0;
                return response;
            }
            
            response.http_code_ = httpResponse.statusCode;
            
            if (httpResponse.statusCode >= 200 && httpResponse.statusCode < 300) {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::SUCCESS;
                
                // Extract Last-Modified header if requested
                if (header_mask & valhalla::baldr::tile_getter_t::kHeaderLastModified) {
                    NSString* lastModified = [httpResponse valueForHTTPHeaderField:@"Last-Modified"];
                    if (lastModified) {
                        NSDateFormatter* formatter = [[NSDateFormatter alloc] init];
                        formatter.dateFormat = @"EEE, dd MMM yyyy HH:mm:ss zzz";
                        formatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"];
                        NSDate* date = [formatter dateFromString:lastModified];
                        response.last_modified_time_ = (uint64_t)[date timeIntervalSince1970];
                    } else {
                        response.last_modified_time_ = 0;
                    }
                }
            } else {
                response.status_ = valhalla::baldr::tile_getter_t::status_code_t::FAILURE;
            }
        }
        
        return response;
    }
};


namespace {

/// One of the actor entry points in `main.h`. They all take a request and an actor,
/// and they all answer with either a serialized response or the wrapper's error
/// envelope — they never throw.
using ActorAction = std::string (*)(const char*, void*);

/// Runs one action against the actor and bridges the response back. Callers hold
/// the lock; this does not.
///
/// A null actor means the caller ran an action after `close`. Swift guards that
/// case first, so reaching here is a bug rather than ordinary use — but the
/// wrapper still has to answer something instead of dereferencing null, and the
/// envelope it answers matches the Android JNI layer's wording exactly.
template <typename Action>
NSString* PerformOnActor(void* actor, const char* action_name, Action&& action) {
    if (actor == nullptr) {
        return [NSString stringWithFormat:
                @"{\"code\":-1,\"message\":\"the actor is closed, cannot run %s\"}",
                action_name];
    }

    std::string result = action(actor);

    // Swift imports this return as implicitly unwrapped, so a nil would trap in
    // the host app. Invalid UTF-8 answers the wrapper's error envelope instead.
    NSString* response = [[NSString alloc] initWithBytes:result.data()
                                                  length:result.size()
                                                encoding:NSUTF8StringEncoding];

    return response ?: @"{\"code\":-1,\"message\":\"response was not valid UTF-8\"}";
}

/// Shared body of every action method that takes a request.
NSString* PerformAction(ActorAction action,
                        NSString* request,
                        void* actor,
                        const char* action_name) {
    return PerformOnActor(actor, action_name, [&](void* actor) {
        return action([request UTF8String], actor);
    });
}

} // namespace


@implementation ValhallaWrapper

- (instancetype)initWithConfigPath:(NSString*)config_path error:(__autoreleasing NSError **)error
{
    self = [super init];
    std::string path = std::string([config_path UTF8String]);
    try {
        // Create the network interface implementation for iOS
        ValhallaMobileHttpClient* httpClient = new ValhallaMobileHttpClientImpl();
        // Owned here, not by the actor, and freed in dealloc rather than close: `cancel`
        // has to reach a RUNNING action without taking the monitor or reading _actor, and
        // close() nulls _actor and frees the actor underneath it.
        _cancelFlag = new std::atomic<bool>(false);
        _actor = create_valhalla_actor(path.c_str(), httpClient,
                                       static_cast<std::atomic<bool>*>(_cancelFlag));
    } catch (NSException *exception) {
        *error = [[NSError alloc] initWithDomain:exception.name code:0 userInfo:@{
            NSUnderlyingErrorKey: exception,
            NSLocalizedDescriptionKey: exception.reason,
            @"CallStackSymbols": exception.callStackSymbols
        }];
        return nil;
    } catch (const std::exception &err) {
        *error = [[NSError alloc] initWithDomain: [NSString stringWithUTF8String:err.what()] code:-1 userInfo: nil];
        return nil;
    } catch (...) {
        *error = [[NSError alloc] initWithDomain: @"unknown exception" code:-1 userInfo: nil];
        return nil;
    }
    return self;
}

- (NSString*)route:(NSString*)request
{
    @synchronized(self) {
        return PerformAction(&route, request, _actor, "route");
    }
}

- (NSString*)traceRoute:(NSString*)request
{
    @synchronized(self) {
        return PerformAction(&trace_route, request, _actor, "trace_route");
    }
}

- (NSString*)traceAttributes:(NSString*)request
{
    @synchronized(self) {
        return PerformAction(&trace_attributes, request, _actor, "trace_attributes");
    }
}

- (NSString*)height:(NSString*)request
{
    @synchronized(self) {
        return PerformAction(&height, request, _actor, "height");
    }
}

- (void)close
{
    // The same lock every action takes, so a close cannot free the actor out from
    // under a request that is already running on another thread.
    @synchronized(self) {
        // Deleting null is well defined, so a double close is harmless.
        delete_valhalla_actor(_actor);
        _actor = nil;
    }
}

- (NSString*)matrix:(NSString*)request
{
    @synchronized(self) {
        return PerformAction(&matrix, request, _actor, "sources_to_targets");
    }
}

- (NSArray<NSDictionary*>*)tilesCoveringLatitude:(double)latitude longitude:(double)longitude
{
    @synchronized(self) {
        if (_actor == nullptr) {
            return @[];
        }
        auto* actor = static_cast<ValhallaActor*>(_actor);
        NSMutableArray<NSDictionary*>* covering = [NSMutableArray array];
        try {
            for (const auto& ref : actor->tiles_covering(latitude, longitude)) {
                [covering addObject:@{
                    @"level" : @(ref.level),
                    @"id" : @(ref.id),
                    @"path" : [NSString stringWithUTF8String:ref.path.c_str()],
                }];
            }
        } catch (...) {
            return @[];
        }
        return covering;
    }
}

- (NSString*)ensureTileCachedAtLevel:(uint32_t)level tileId:(uint32_t)tileId
{
    @synchronized(self) {
        return PerformOnActor(_actor, "ensure_tile_cached", [&](void* actor) {
            return ensure_tile_cached(level, tileId, actor);
        });
    }
}

- (void)cancel
{
    // NOT @synchronized, and it does not touch _actor. The whole point is to reach an action
    // that is RUNNING, and every other method holds the monitor for its duration -- so taking
    // it here would mean waiting for the thing being cancelled. Reading _actor without the
    // monitor would race with close(), which nulls it and frees the actor, so the flag lives
    // here instead and outlives the actor by construction.
    static_cast<std::atomic<bool>*>(_cancelFlag)->store(true, std::memory_order_relaxed);
}

- (void)resume
{
    static_cast<std::atomic<bool>*>(_cancelFlag)->store(false, std::memory_order_relaxed);
}

- (void) dealloc
{
    [self close];
    // After close, so nothing can still be reading it.
    delete static_cast<std::atomic<bool>*>(_cancelFlag);
    _cancelFlag = nullptr;
}

@end
