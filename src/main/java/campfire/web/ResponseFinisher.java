package campfire.web;

import campfire.App;
import campfire.view.PageDigest;
import campfire.view.Pages;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import org.jspecify.annotations.Nullable;

/// Runs after every resource method, errors included: writes the session and queued cookies, the
/// version and security headers, the stylesheet preload `Link`, a content ETag (answering 304 when
/// the client has it), offers the page to the response caches, and gzips text for a client that
/// takes it.
public class ResponseFinisher {
    @Inject
    RequestOrigin origin;
    @Inject
    App app;
    @Inject
    ResponseCache cache;
    @Inject
    Front front;
    @Inject
    Pages pages;

    @ServerResponseFilter
    public void finish(ContainerRequestContext context, ContainerResponseContext response, RoutingContext routing) {
        HttpServerRequest request = routing.request();
        RequestState state = RequestState.of(routing);
        MultivaluedMap<String, Object> headers = response.getHeaders();
        if (state.session != null) state.session.commit();
        headers.putSingle("X-Version", app.config().appVersion());
        app.config().gitRevision().filter(r -> !r.isEmpty()).ifPresent(r -> headers.putSingle("X-Rev", r));
        if (origin.ssl()) headers.putSingle("Strict-Transport-Security", "max-age=63072000; includeSubDomains");
        if (pages.stylesheetsLinked()) linkStylesheets(headers);
        if (response.getEntity() == null && header(response, "Cache-Control") == null) headers.putSingle("Cache-Control", "no-cache");
        readSmallFile(response);
        boolean gzipAccepted = Gzip.accepted(request.getHeader("accept-encoding"));
        if (response.getEntity() instanceof byte[] body) {
            etag(response, body);
            if (response.getStatus() == 200 && (request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD) && notModified(response, request)) {
                response.setStatus(304);
                response.setEntity(null);
                headers.remove("Content-Type");
                writeCookies(state, routing);
                return;
            }
            if (state.cacheTicket != null) {
                byte[] gzip = state.cachedGzip != null ? state.cachedGzip : gzipAccepted && compressible(response) ? Gzip.compress(body) : null;
                cache.admit(state, app.db()::dataVersion, response.getStatus(), liveHeaders(response), body, gzip);
                state.cachedGzip = gzip;
            }
        }
        byte[] plain = response.getEntity() instanceof byte[] body ? body : null;
        byte[] gzip = encode(response, gzipAccepted, state);
        offer(response, routing, state, plain, gzip);
        writeCookies(state, routing);
    }

    private static void writeCookies(RequestState state, RoutingContext routing) {
        if (state.cookies != null) state.cookies.writeTo(routing.response());
    }

    /// Appends the stylesheet preloads to any `Link` the page set itself.
    private void linkStylesheets(MultivaluedMap<String, Object> headers) {
        String links = app.assets().stylesheetLinkHeader();
        Object existing = headers.getFirst("Link");
        headers.putSingle("Link", existing == null || existing.toString().isEmpty() ? links : existing + "," + links);
    }

    /// Avatars, logos and icons go out whole, which lets the caches keep them; large files stream.
    private static void readSmallFile(ContainerResponseContext response) {
        if (!(response.getEntity() instanceof Path file)) return;
        try {
            if (Files.size(file) <= FrontCache.MAX_ITEM_SIZE) response.setEntity(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Never a 206: gzipping a range would make its `Content-Range` wrong.
    private static boolean compressible(ContainerResponseContext response) {
        int status = response.getStatus();
        return status >= 200 && status != 204 && status != 206 && status != 304 && header(response, "Content-Encoding") == null
            && Gzip.compressible(header(response, "Content-Type"));
    }

    /// Gzips a text body for a client that takes it; a cache hit brings its stored gzip along.
    /// Returns a whole body gzipped, when it was.
    private static byte @Nullable [] encode(ContainerResponseContext response, boolean gzipAccepted, RequestState state) {
        Object entity = response.getEntity();
        if (entity == null || entity instanceof byte[] b && b.length == 0 || !compressible(response)) return null;
        addVary(response.getHeaders(), "Accept-Encoding");
        if (!gzipAccepted) return null;
        response.getHeaders().putSingle("Content-Encoding", "gzip");
        response.getHeaders().remove("Content-Length");
        response.setEntity(switch (entity) {
            case byte[] body when state.cachedGzip != null -> state.cachedGzip;
            case byte[] body -> Gzip.compress(body);
            case String text -> Gzip.compress(text.getBytes(StandardCharsets.UTF_8));
            case Path file -> (StreamingOutput) out -> {
                try (var gzip = Gzip.stream(out)) {
                    Files.copy(file, gzip);
                }
            };
            case StreamingOutput body -> (StreamingOutput) out -> {
                try (var gzip = Gzip.stream(out)) {
                    body.write(gzip);
                }
            };
            default -> throw new IllegalStateException("cannot gzip a " + entity.getClass().getName());
        });
        return response.getEntity() instanceof byte[] gzip ? gzip : null;
    }

    static void addVary(MultivaluedMap<String, Object> headers, String name) {
        Object existing = headers.getFirst("Vary");
        if (existing == null || existing.toString().isBlank()) {
            headers.putSingle("Vary", name);
            return;
        }
        for (String present : existing.toString().split(",")) {
            if (present.strip().equalsIgnoreCase(name) || present.strip().equals("*")) return;
        }
        headers.putSingle("Vary", existing + ", " + name);
    }

    /// A public response goes out without cookies, as it may be served to anyone, and a whole one
    /// is offered to the public cache, plain and gzipped.
    private void offer(ContainerResponseContext response, RoutingContext routing, RequestState state, byte @Nullable [] plain,
                       byte @Nullable [] gzip) {
        String vary = header(response, "Vary");
        Duration lifetime = FrontCache.lifetime(response.getStatus(), header(response, "Cache-Control"), vary);
        if (lifetime == null) return;
        if (state.cookies != null) state.cookies.discard();
        if (plain == null) return;
        MultivaluedMap<String, Object> responseHeaders = response.getHeaders();
        var headers = new ArrayList<FrontCache.Header>();
        responseHeaders.forEach((name, values) -> {
            if (gzip != null && name.equalsIgnoreCase("Content-Encoding") || name.equalsIgnoreCase("Content-Length")) return;
            for (Object value : values) headers.add(new FrontCache.Header(name, String.valueOf(value)));
        });
        byte[] gzipped = gzip == null && vary != null && responseHeaders.getFirst("Content-Encoding") == null ? Gzip.compress(plain) : gzip;
        front.offer(routing, response.getStatus(), headers, plain, gzipped, lifetime);
    }

    /// A weak ETag of the first 32 hex digits of the body's SHA-256 (or its {@link PageDigest} when
    /// it was rendered with cached fragments), for non-empty 200s and 201s that carry no validator
    /// of their own; plus the default `Cache-Control` either way.
    private static void etag(ContainerResponseContext response, byte[] body) {
        boolean tagged = false;
        int status = response.getStatus();
        if ((status == 200 || status == 201) && body.length > 0 && header(response, "ETag") == null && header(response, "Last-Modified") == null) {
            String parts = PageDigest.of(body);
            response.getHeaders().putSingle("ETag", new EntityTag(parts != null ? parts : sha256Hex(body).substring(0, 32), true));
            tagged = true;
        }
        if (header(response, "Cache-Control") == null) {
            response.getHeaders().putSingle("Cache-Control", tagged ? HttpCache.REVALIDATE : "no-cache");
        }
    }

    private static String strong(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }

    /// `If-None-Match` wins over `If-Modified-Since`; weak and strong tags compare equal.
    private static boolean notModified(ContainerResponseContext response, HttpServerRequest request) {
        String etag = header(response, "ETag");
        String ifNoneMatch = request.getHeader("if-none-match");
        if (etag != null && ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",")) {
                String c = candidate.strip();
                if (c.equals("*") || c.equals(etag) || strong(c).equals(strong(etag))) return true;
            }
            return false;
        }
        String lastModified = header(response, "Last-Modified");
        return lastModified != null && lastModified.equals(request.getHeader("if-modified-since"));
    }

    /// The headers as they stand: `getStringHeaders()` is a snapshot from before this filter ran.
    private static Map<String, List<String>> liveHeaders(ContainerResponseContext response) {
        var headers = new LinkedHashMap<String, List<String>>();
        response.getHeaders().forEach((name, values) -> {
            var strings = new ArrayList<String>(values.size());
            for (Object value : values) strings.add(String.valueOf(value));
            headers.put(name, strings);
        });
        return headers;
    }

    private static @Nullable String header(ContainerResponseContext response, String name) {
        return response.getHeaderString(name);
    }

    private static String sha256Hex(byte[] body) {
        return HexFormat.of().formatHex(PageDigest.sha256().digest(body));
    }
}
