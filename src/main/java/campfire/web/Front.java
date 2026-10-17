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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Vert.x handlers in front of the REST resources, outermost first:
///
/// - the proxy layer the Rails image runs behind (Thruster): the request size limit, the
///   `X-Request-Start` and `X-Forwarded-*` request headers, the shared cache of public responses
///   ({@link FrontCache}, answering `X-Cache: hit`, `miss` or `bypass`), the `Vary` its
///   compression adds, and the headers a bodiless status can't carry;
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
    /// What this request did at the cache: a {@link FrontCache.Miss} or {@link #BYPASS}.
    static final String CACHE_STATE = "campfire.front.cache";
    private static final String BYPASS = "bypass";
    private static final String FINISHED = "campfire.front.finished";
    /// When the request arrived, in `System.nanoTime()`, for `X-Runtime`.
    static final String STARTED = "campfire.front.started";

    @Inject
    Config config;
    @Inject
    Assets assets;
    @Inject
    Cable cable;

    private final FrontConfig front = FrontConfig.fromEnv();
    private final FrontCache cache = new FrontCache(front.cacheSize(), front.maxCacheItemSize());
    private final FrontCompression compression = new FrontCompression(front.jitter(), front.compressionDisabledOnAuth());
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
            if (method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE")) rc.reroute(HttpMethod.valueOf(method), rc.request().path());
            else rc.next();
        });
    }

    FrontConfig config() {
        return front;
    }

    FrontCompression compression() {
        return compression;
    }

    private void proxy(RoutingContext rc) {
        rc.put(STARTED, System.nanoTime());
        HttpServerRequest request = rc.request();
        HttpServerResponse response = rc.response();
        boolean cacheable = FrontCache.cacheable(request);
        rc.addHeadersEndHandler(v -> finishHeaders(rc, cacheable));
        if (tooLarge(request)) {
            response.setStatusCode(413).end();
            return;
        }
        MultiMap headers = request.headers();
        String start = headers.get("x-request-start");
        if (start == null || start.isEmpty()) headers.set("X-Request-Start", "t=" + System.currentTimeMillis());
        if (!cacheable) {
            rc.put(CACHE_STATE, BYPASS);
            // Vert.x writes a WebSocket handshake without the headers-end callbacks, so what they
            // would add goes on now (the handshake takes the response's headers).
            if ("websocket".equalsIgnoreCase(request.getHeader("upgrade"))) {
                response.headers().set("x-request-id", requestId(request.getHeader("x-request-id")));
                response.headers().set("x-runtime", runtime(rc));
                finishHeaders(rc, false);
            }
        } else {
            switch (cache.lookup(request)) {
                case FrontCache.Hit(FrontCache.Stored stored) -> {
                    serveHit(request, response, stored);
                    return;
                }
                case FrontCache.Missed(FrontCache.Miss miss) -> rc.put(CACHE_STATE, miss);
            }
        }
        forward(request);
        rc.next();
    }

    /// Rails' request id: the client's, cut down to word characters, `-` and `@` (at most 255),
    /// else a new random (version 4) UUID. It's a tracing id, not a secret, so it comes from
    /// `ThreadLocalRandom` rather than `UUID.randomUUID()`'s shared `SecureRandom`.
    static String requestId(@Nullable String incoming) {
        if (incoming == null || incoming.isBlank()) {
            var random = ThreadLocalRandom.current();
            return new UUID((random.nextLong() & ~0xF000L) | 0x4000L, (random.nextLong() & ~(3L << 62)) | (2L << 62)).toString();
        }
        var sanitized = new StringBuilder();
        incoming.codePoints().filter(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '@').limit(255)
            .forEach(sanitized::appendCodePoint);
        return sanitized.toString();
    }

    /// Seconds since the request arrived, to the microsecond.
    static String runtime(RoutingContext rc) {
        Long started = rc.get(STARTED);
        double seconds = started == null ? 0 : (System.nanoTime() - started) / 1e9;
        return String.format(java.util.Locale.ROOT, "%.6f", seconds);
    }

    /// An empty 413 for a declared body over the limit (0 is none), before the app sees it.
    private boolean tooLarge(HttpServerRequest request) {
        if (front.maxRequestBody() <= 0) return false;
        String length = request.getHeader("content-length");
        try {
            return length != null && Long.parseLong(length.strip()) > front.maxRequestBody();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /// The headers a reverse proxy adds: the client's address appended to `X-Forwarded-For`, and
    /// this request's host and scheme. Without TLS the client's own `X-Forwarded-*` are trusted
    /// and kept, as Thruster keeps them.
    private static void forward(HttpServerRequest request) {
        MultiMap headers = request.headers();
        String prior = String.join(", ", headers.getAll("x-forwarded-for"));
        String incomingHost = headers.get("x-forwarded-host");
        String incomingProto = headers.get("x-forwarded-proto");
        String host = headers.get("host");
        String client = request.remoteAddress() == null ? "" : request.remoteAddress().hostAddress();
        headers.remove("forwarded");
        headers.set("X-Forwarded-For", prior.isEmpty() ? client : prior + ", " + client);
        headers.set("X-Forwarded-Host", incomingHost != null && !incomingHost.isEmpty() ? incomingHost : host == null ? "" : host);
        headers.set("X-Forwarded-Proto", incomingProto != null && !incomingProto.isEmpty() ? incomingProto : "http");
    }

    private void serveHit(HttpServerRequest request, HttpServerResponse response, FrontCache.Stored stored) {
        boolean notModified = FrontCache.notModified(stored, request);
        response.setStatusCode(notModified ? 304 : stored.status());
        for (FrontCache.Header h : stored.headers()) response.headers().add(h.name(), h.value());
        response.headers().set("x-cache", "hit");
        if (notModified) response.end();
        else response.end(Buffer.buffer(stored.body()));
    }

    /// Last touches as the headers go out: what the cache did, the compression's `Vary`, and no
    /// length or type on statuses that have no body.
    private void finishHeaders(RoutingContext rc, boolean cacheable) {
        if (rc.get(FINISHED) != null) return;
        rc.put(FINISHED, true);
        HttpServerResponse response = rc.response();
        MultiMap headers = response.headers();
        boolean bypassed = !cacheable;
        if (!headers.contains("x-cache")) headers.set("x-cache", bypassed ? BYPASS : "miss");
        if (front.compression()) FrontCompression.addVary(headers, bypassed);
        int status = response.getStatusCode();
        if (status == 304) {
            headers.remove("content-type");
            headers.remove("content-length");
            headers.remove("transfer-encoding");
        } else if (status < 200 || status == 204) {
            headers.remove("content-length");
            headers.remove("transfer-encoding");
        }
        lowercaseNames(headers);
    }

    /// Rack 3 (and so Rails and the Rust port) sends every header name in lower case. The map
    /// ignores case, so a name is renamed by taking all its values out and putting them back;
    /// renamed headers move to the end, and the order of headers isn't part of the contract.
    private static void lowercaseNames(MultiMap headers) {
        List<String> mixedCase = null;
        for (Map.Entry<String, String> header : headers) {
            String name = header.getKey();
            if (hasUpperCase(name) && (mixedCase == null || !mixedCase.contains(name))) {
                if (mixedCase == null) mixedCase = new ArrayList<>(4);
                mixedCase.add(name);
            }
        }
        if (mixedCase == null) return;
        for (String name : mixedCase) {
            List<String> values = headers.getAll(name);
            headers.remove(name);
            headers.add(name.toLowerCase(Locale.ROOT), values);
        }
    }

    private static boolean hasUpperCase(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') return true;
        }
        return false;
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
                if (!name.equalsIgnoreCase("set-cookie") && !name.equalsIgnoreCase("content-length") && !name.equalsIgnoreCase("x-cache")) kept.add(h);
            }
            cache.store(miss, status, kept, rc.request().method() == HttpMethod.HEAD ? new byte[0] : body, lifetime);
        }
        return true;
    }

    private static @Nullable String first(List<FrontCache.Header> headers, String name) {
        for (FrontCache.Header h : headers) if (h.name().equalsIgnoreCase(name)) return h.value();
        return null;
    }

    /// `public/` and the digested assets. Like everything else they're gzipped for a client that
    /// takes it, from the precompressed copy where there is one.
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
        String encoding = ContentEncoding.negotiate(request.getHeader("accept-encoding"));
        if (encoding == null) {
            notAcceptable(request, response);
            return true;
        }
        var headers = new ArrayList<FrontCache.Header>();
        headers.add(new FrontCache.Header("Last-Modified", LAST_MODIFIED));
        headers.add(new FrontCache.Header("Content-Type", file.contentType()));
        headers.add(new FrontCache.Header("Cache-Control", "public, max-age=2592000"));
        headers.add(new FrontCache.Header("Vary", "Accept-Encoding"));
        byte[] body = file.body();
        if (encoding.equals("gzip") && body.length > 0) {
            headers.add(new FrontCache.Header("Content-Encoding", "gzip"));
            Assets.StaticFile f = file;
            body = f.gzip() != null ? f.gzip() : gzipped.computeIfAbsent(served,
                k -> Gzip.compress(f.body(), ContentEncoding.mtime(LAST_MODIFIED), null));
        }
        offer(rc, 200, headers, body);
        for (FrontCache.Header h : headers) response.headers().add(h.name(), h.value());
        response.end(Buffer.buffer(body));
        return true;
    }

    /// The plain-text 406 for a client that takes neither gzip nor identity.
    static void notAcceptable(HttpServerRequest request, HttpServerResponse response) {
        response.setStatusCode(406).putHeader("Content-Type", "text/plain").end(Buffer.buffer(ContentEncoding.notAcceptable(request.uri())));
    }
}
