package campfire.web;

import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/// What the stages serving one request tell each other: the format from the path, the access
/// filter's data version and the response cache's ticket. It lives on the Vert.x `RoutingContext`, which every stage reaches without going
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
    /// The request's cookie jar and Rails session, once something used them: most requests
    /// don't, and then the finisher has nothing to write.
    @Nullable Cookies cookies;
    @Nullable RailsSession session;

    private RequestState() {}

    /// Leaves the session and cookies as the request brought them. Rails renders an error page
    /// after the action raised, and the session middleware then writes nothing.
    void discardChanges() {
        if (session != null) session.discardChanges();
        if (cookies != null) cookies.discard();
    }

    /// See {@link #dataVersionAtStart}.
    public static long dataVersionAtStart(RoutingContext routing) {
        return of(routing).dataVersionAtStart;
    }

    static RequestState of(RoutingContext routing) {
        RequestState state = routing.get(KEY);
        if (state == null) routing.put(KEY, state = new RequestState());
        return state;
    }
}
