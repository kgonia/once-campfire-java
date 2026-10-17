package campfire.web;

import campfire.assets.Assets;
import campfire.cable.Cable;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Vert.x handlers in front of the REST resources, outermost first:
///
/// - the shared cache of public responses ({@link FrontCache}) and static files, answered from
///   memory on the event loop, ahead of Quarkus' security handlers (route orders -200 and -100),
///   which public responses don't need;
/// - the cable WebSocket.
@ApplicationScoped
@NullMarked
public class Front {
    /// Static files carry the time the app started as their Last-Modified: they're compiled
    /// into it, so they can't have changed since.
    private static final String LAST_MODIFIED = HttpCache.httpDate(Instant.now());
    private static final Duration STATIC_LIFETIME = Duration.ofDays(30);
    private static final String STATIC_CACHE_CONTROL = "public, max-age=" + STATIC_LIFETIME.toSeconds();
    /// The URI a public response to this GET would be cached under.
    static final String CACHE_STATE = "campfire.front.cache";

    @Inject
    Assets assets;
    @Inject
    Cable cable;

    private final FrontCache cache = new FrontCache();
    /// Gzipped `public/` files that weren't precompressed, by path.
    private final Map<String, byte[]> gzipped = new ConcurrentHashMap<>();

    void routes(@Observes Router router) {
        router.route().order(-310).handler(this::cached);
        router.route().order(-300).handler(rc -> {
            if (!serveStatic(rc)) rc.next();
        });
        router.route("/cable").order(-30).handler(cable::upgrade);
    }

    private void cached(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        if (FrontCache.cacheable(request)) {
            FrontCache.Stored stored = cache.lookup(request.uri());
            if (stored != null) {
                serveHit(request, rc.response(), stored);
                return;
            }
            rc.put(CACHE_STATE, request.uri());
        }
        rc.next();
    }

    /// The request's host and port: the `Host` header over HTTP/1.1, the `:authority` pseudo-header
    /// over HTTP/2 (which has no `Host`), or a trusted proxy's `X-Forwarded-Host`. Null when the
    /// client sent none.
    public static @Nullable String authority(HttpServerRequest request) {
        var authority = request.authority();
        if (authority == null) return null;
        return authority.port() < 0 ? authority.host() : authority.host() + ":" + authority.port();
    }

    private static void serveHit(HttpServerRequest request, HttpServerResponse response, FrontCache.Stored stored) {
        boolean notModified = FrontCache.notModified(stored, request);
        response.setStatusCode(notModified ? 304 : stored.status());
        for (FrontCache.Header h : stored.headers()) response.headers().add(h.name(), h.value());
        if (notModified) {
            response.end();
        } else if (stored.gzip() != null && Gzip.accepted(request.getHeader("accept-encoding"))) {
            response.headers().add("Content-Encoding", "gzip");
            response.end(Buffer.buffer(stored.gzip()));
        } else {
            response.end(Buffer.buffer(stored.body()));
        }
    }

    /// Keeps a public response for the GETs that missed the cache. `headers` carry no cookies or
    /// encoding, and `gzip` is the body gzipped when the response varies on `Accept-Encoding`.
    void offer(RoutingContext rc, int status, List<FrontCache.Header> headers, byte[] body, byte @Nullable [] gzip, Duration lifetime) {
        if (rc.get(CACHE_STATE) instanceof String uri) cache.store(uri, new FrontCache.Stored(status, List.copyOf(headers), body, gzip, lifetime));
    }

    /// `public/` and the digested assets, text gzipped for a client that takes it, from the
    /// precompressed copy where there is one.
    private boolean serveStatic(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        HttpMethod method = request.method();
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) return false;
        String path = rc.normalizedPath();
        Assets.StaticFile file = assets.file(path);
        String served = path;
        if (file == null && !path.startsWith("/assets/") && !path.contains(".")) {
            served = path + ".html";
            file = assets.file(served);
        }
        if (file == null && path.endsWith("/") && path.length() > 1) {
            served = path.substring(0, path.length() - 1) + ".html";
            file = assets.file(served);
        }
        if (file == null) return false;
        HttpServerResponse response = rc.response();
        if (LAST_MODIFIED.equals(request.getHeader("if-modified-since"))) {
            response.setStatusCode(304).end();
            return true;
        }
        var headers = new ArrayList<FrontCache.Header>();
        headers.add(new FrontCache.Header("Last-Modified", LAST_MODIFIED));
        headers.add(new FrontCache.Header("Content-Type", file.contentType()));
        headers.add(new FrontCache.Header("Cache-Control", STATIC_CACHE_CONTROL));
        byte[] gzip = null;
        if (Gzip.compressible(file.contentType()) && file.body().length > 0) {
            headers.add(new FrontCache.Header("Vary", "Accept-Encoding"));
            Assets.StaticFile f = file;
            gzip = f.gzip() != null ? f.gzip() : gzipped.computeIfAbsent(served, k -> Gzip.compress(f.body()));
        }
        offer(rc, 200, headers, file.body(), gzip, STATIC_LIFETIME);
        for (FrontCache.Header h : headers) response.headers().add(h.name(), h.value());
        if (gzip != null && Gzip.accepted(request.getHeader("accept-encoding"))) {
            response.headers().add("Content-Encoding", "gzip");
            response.end(Buffer.buffer(gzip));
        } else {
            response.end(Buffer.buffer(file.body()));
        }
        return true;
    }
}
