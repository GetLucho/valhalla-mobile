#ifndef ValhallaWrapperHeader_h
#define ValhallaWrapperHeader_h

#import <Foundation/Foundation.h>

@class ValhallaWrapper;

@interface ValhallaWrapper : NSObject {
    @private
    void* _actor;
    /// std::atomic<bool>*, owned here and freed in dealloc. See `cancel`.
    void* _cancelFlag;
}

- (instancetype)initWithConfigPath:(NSString*)config_path error:(__autoreleasing NSError **)error;

/// Releases the native actor, and with it the mmapped tile extract.
///
/// Safe to call more than once. Every action afterwards answers the wrapper's
/// error envelope rather than touching the freed actor — byte for byte what the
/// Android JNI layer answers for a call after close. `dealloc` calls this, so a
/// caller that never closes still frees the actor.
- (void)close;

- (NSString*)route:(NSString*)request;

/// Map-matches a GPS trace and returns a route along the matched path.
/// @param request a `trace_route` request as JSON.
- (NSString*)traceRoute:(NSString*)request;

/// Map-matches a GPS trace and returns the attributes of every edge along the matched path.
/// @param request a `trace_attributes` request as JSON.
- (NSString*)traceAttributes:(NSString*)request;

/// Samples terrain heights under a shape, from the configured elevation tiles.
/// @param request a `height` request as JSON.
- (NSString*)height:(NSString*)request;

/// The tiles covering a coordinate, one per hierarchy level.
///
/// Each entry is `@{@"level": NSNumber, @"id": NSNumber, @"path": NSString}`. The path is
/// what `mjolnir.tile_url`'s {tilePath} is replaced with, and it always carries the
/// uncompressed suffix: with `tile_url_gz` on the CACHED file is .gph.gz, but the URL is
/// unchanged, so the two differ deliberately.
///
/// Empty for a coordinate that is not on the planet, and after `close`.
/// NS_SWIFT_NAME because the importer would otherwise derive `tilesCoveringLatitude(_:longitude:)`
/// from the selector, which reads badly at every call site.
- (NSArray<NSDictionary*>* _Nonnull)tilesCoveringLatitude:(double)latitude longitude:(double)longitude
    NS_SWIFT_NAME(tilesCovering(latitude:longitude:));

/// Ensures one tile is in `mjolnir.tile_dir`, fetching it through valhalla if it is not.
///
/// `true` or `false` as JSON, or the error envelope when the fetch was cancelled, hit the
/// deadline, or failed. `false` is a tile the origin does not have, which is normal coverage
/// rather than a failure: two of the sixteen level-2 tiles over Lake and Porter counties are
/// Lake Michigan.
- (NSString*)ensureTileCachedAtLevel:(uint32_t)level tileId:(uint32_t)tileId
    NS_SWIFT_NAME(ensureTileCached(level:id:));

/// Asks the action running now to stop at its next tile fetch. Sticky until `resume`.
- (void)cancel;

/// Clears a previous `cancel` so further actions can run.
- (void)resume;

/// Computes a matrix of costs and times between every source and every target.
/// @param request a `sources_to_targets` request as JSON.
- (NSString*)matrix:(NSString*)request;

@end

#endif /* ValhallaWrapperHeader_h */
