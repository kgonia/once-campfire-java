package campfire.web;

import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/// What the stages serving one request tell each other: the format from the path, the access
/// filter's data version, the response cache's ticket, and how the finisher should treat the
/// response. It lives on the Vert.x `RoutingContext`, which every stage reaches without going
/// through a request-scoped CDI proxy: Quarkus REST passes it to filter methods that ask for it.
public final class RequestState {
    private static final String KEY = "campfire.request.state";

    /// The `.json`/`.js` suffix the path came with, or a route's own default.
    @Nullable String format;
    /// The database's data version when the access filter admitted the request, read once and
    /// shared by the session lookup and `ResponseCache`; -1 for framework endpoints.
    long dataVersionAtStart = -1;
    ResponseCache.@Nullable Ticket cacheTicket;
    /// The gzipped body that came with a cache hit, so it isn't compressed again.
    byte @Nullable [] cachedGzip;
    /// File downloads skip the page security headers and the content ETag, as Rails' streamed
    /// responses do.
    boolean download;
    /// Served by one of Rails' own controllers (Active Storage, Turbo Native) rather than the
    /// app's: none of the app's checks or version headers apply.
    boolean framework;
    /// One of Rails' exception pages, which bypass the controller: no session commit, cookies,
    /// default or version headers, ETag or Cache-Control.
    boolean exceptionResponse;

    private RequestState() {}

    static RequestState of(RoutingContext routing) {
        RequestState state = routing.get(KEY);
        if (state == null) routing.put(KEY, state = new RequestState());
        return state;
    }
}
