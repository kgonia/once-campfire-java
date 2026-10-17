package campfire.web;

import campfire.Config;
import campfire.assets.Assets;
import campfire.cable.Cable;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
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
/// - the `X-Forwarded-*` request headers and the shared cache of public responses
///   ({@link FrontCache}), answered on the event loop;
/// - the cable WebSocket;
/// - static files straight from memory on the event loop;
/// - request bodies (form fields and uploads, read once for everything after), and the `_method`
///   field HTML forms use to send PUT, PATCH and DELETE.
@ApplicationScoped
@NullMarked
public class Front {
    /// Static files carry the time the app started as their Last-Modified: they're compiled
    /// into it, so they can't have changed since.
    private static final String LAST_MODIFIED = CurrentRequest.httpDate(Instant.now());
    /// Where a public response to this request would be cached: a {@link FrontCache.Miss}.
    static final String CACHE_STATE = "campfire.front.cache";

    @Inject
    Config config;
    @Inject
    Assets assets;
    @Inject
    Cable cable;

    private final FrontCache cache = new FrontCache();
    /// Gzipped `public/` files that weren't precompressed, by path.
    private final Map<String, byte[]> gzipped = new ConcurrentHashMap<>();

    void routes(@Observes Router router) {
        BodyHandler bodies = BodyHandler.create(config.storage().resolve("tmp").resolve("uploads").toString())
            .setBodyLimit(config.maxRequestBody()).setDeleteUploadedFilesOnEnd(true).setMergeFormAttributes(false);
        router.route().order(-40).handler(this::proxy);
        router.route("/cable").order(-30).handler(cable::upgrade);
        router.route().order(-20).handler(rc -> {
            if (serveStatic(rc)) return;
            HttpMethod method = rc.request().method();
            if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.OPTIONS) rc.next();
            else bodies.handle(rc);
        });
        router.route().order(-10).method(HttpMethod.POST).handler(rc -> {
            String override = rc.request().getFormAttribute("_method");
            String method = override == null ? "" : override.toUpperCase(java.util.Locale.ROOT);
            // The URI, not the path: Rails merges the query string into params for every method.
            if (method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE")) rc.reroute(HttpMethod.valueOf(method), rc.request().uri());
            else rc.next();
        });
    }

    private void proxy(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        if (FrontCache.cacheable(request)) {
            switch (cache.lookup(request)) {
                case FrontCache.Hit(FrontCache.Stored stored) -> {
                    serveHit(request, rc.response(), stored);
                    return;
                }
                case FrontCache.Missed(FrontCache.Miss miss) -> rc.put(CACHE_STATE, miss);
            }
        }
        forward(request);
        rc.next();
    }

    /// The request's host and port: the `Host` header over HTTP/1.1, the `:authority` pseudo-header
    /// over HTTP/2 (which has no `Host`). Null when the client sent neither.
    public static @Nullable String authority(HttpServerRequest request) {
        var authority = request.authority();
        if (authority == null) return null;
        return authority.port() < 0 ? authority.host() : authority.host() + ":" + authority.port();
    }

    /// The headers a reverse proxy adds: the client's address appended to `X-Forwarded-For`, and
    /// this request's host and scheme. Without TLS the client's own `X-Forwarded-*` are trusted
    /// and kept, as Thruster keeps them.
    private static void forward(HttpServerRequest request) {
        MultiMap headers = request.headers();
        String prior = String.join(", ", headers.getAll("x-forwarded-for"));
        String incomingHost = headers.get("x-forwarded-host");
        String incomingProto = headers.get("x-forwarded-proto");
        String host = authority(request);
        String client = request.remoteAddress() == null ? "" : request.remoteAddress().hostAddress();
        headers.remove("forwarded");
        headers.set("X-Forwarded-For", prior.isEmpty() ? client : prior + ", " + client);
        headers.set("X-Forwarded-Host", incomingHost != null && !incomingHost.isEmpty() ? incomingHost : host == null ? "" : host);
        headers.set("X-Forwarded-Proto", incomingProto != null && !incomingProto.isEmpty() ? incomingProto : "http");
    }

    private static void serveHit(HttpServerRequest request, HttpServerResponse response, FrontCache.Stored stored) {
        boolean notModified = FrontCache.notModified(stored, request);
        response.setStatusCode(notModified ? 304 : stored.status());
        for (FrontCache.Header h : stored.headers()) response.headers().add(h.name(), h.value());
        if (notModified) response.end();
        else response.end(Buffer.buffer(stored.body()));
    }

    /// Offers a response the app produced to the cache: kept when it's public, and then without
    /// its cookies (it may be served to anyone). Returns whether it was public.
    boolean offer(RoutingContext rc, int status, List<FrontCache.Header> headers, byte[] body) {
        Duration lifetime = FrontCache.lifetime(status, first(headers, "cache-control"), first(headers, "vary"));
        if (lifetime == null) return false;
        if (rc.get(CACHE_STATE) instanceof FrontCache.Miss miss) {
            var kept = new ArrayList<FrontCache.Header>();
            for (FrontCache.Header h : headers) {
                String name = h.name();
                if (!name.equalsIgnoreCase("set-cookie") && !name.equalsIgnoreCase("content-length")) kept.add(h);
            }
            cache.store(miss, status, kept, rc.request().method() == HttpMethod.HEAD ? new byte[0] : body, lifetime);
        }
        return true;
    }

    private static @Nullable String first(List<FrontCache.Header> headers, String name) {
        for (FrontCache.Header h : headers) if (h.name().equalsIgnoreCase(name)) return h.value();
        return null;
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
        headers.add(new FrontCache.Header("Cache-Control", "public, max-age=2592000"));
        byte[] body = file.body();
        if (Gzip.compressible(file.contentType()) && body.length > 0) {
            headers.add(new FrontCache.Header("Vary", "Accept-Encoding"));
            if (Gzip.accepted(request.getHeader("accept-encoding"))) {
                headers.add(new FrontCache.Header("Content-Encoding", "gzip"));
                Assets.StaticFile f = file;
                body = f.gzip() != null ? f.gzip() : gzipped.computeIfAbsent(served, k -> Gzip.compress(f.body()));
            }
        }
        offer(rc, 200, headers, body);
        for (FrontCache.Header h : headers) response.headers().add(h.name(), h.value());
        response.end(Buffer.buffer(body));
        return true;
    }
}
