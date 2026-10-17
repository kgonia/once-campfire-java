package campfire.web;

import campfire.App;
import campfire.view.PageDigest;
import campfire.view.Pages;
import io.quarkus.arc.ClientProxy;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    CurrentRequest current;
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
        // Each call through the request-scoped client proxy looks the instance up again.
        CurrentRequest request = ClientProxy.unwrap(current);
        RequestState state = RequestState.of(routing);
        MultivaluedMap<String, Object> headers = response.getHeaders();
        request.commitSession();
        for (String cookie : request.setCookies()) headers.add("Set-Cookie", cookie);
        headers.putSingle("X-Version", app.config().appVersion());
        app.config().gitRevision().filter(r -> !r.isEmpty()).ifPresent(r -> headers.putSingle("X-Rev", r));
        headers.putSingle("x-frame-options", "SAMEORIGIN");
        headers.putSingle("x-content-type-options", "nosniff");
        headers.putSingle("x-xss-protection", "0");
        headers.putSingle("x-permitted-cross-domain-policies", "none");
        headers.putSingle("referrer-policy", "strict-origin-when-cross-origin");
        if (request.ssl()) headers.putSingle("strict-transport-security", "max-age=63072000; includeSubDomains");
        if (pages.stylesheetsLinked()) linkStylesheets(headers);
        // Turbo Streams are always rendered, and a render whose format came from `Accept` says so.
        String type = header(response, "Content-Type");
        if (type != null && type.startsWith("text/vnd.turbo-stream.html") && header(response, "Vary") == null && Formats.variesOnAccept(request)) {
            headers.putSingle("Vary", "Accept");
        }
        if (response.getEntity() == null && header(response, "Cache-Control") == null) headers.putSingle("Cache-Control", "no-cache");
        readSmallFile(response);
        boolean gzipAccepted = Gzip.accepted(request.header("accept-encoding"));
        if (response.getEntity() instanceof byte[] body) {
            etag(response, body);
            if (response.getStatus() == 200 && request.isGet() && notModified(response, request)) {
                response.setStatus(304);
                response.setEntity(null);
                headers.remove("Content-Type");
                return;
            }
            if (state.cacheTicket != null) {
                byte[] gzip = state.cachedGzip != null ? state.cachedGzip : gzipAccepted && compressible(response) ? Gzip.compress(body) : null;
                cache.admit(request, response.getStatus(), liveHeaders(response), body, gzip);
                state.cachedGzip = gzip;
            }
        }
        encode(response, gzipAccepted, state);
        offer(response, routing);
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

    /// Not a 206: gzipping a range would leave its `Content-Range` pointing at the uncompressed bytes.
    private static boolean compressible(ContainerResponseContext response) {
        int status = response.getStatus();
        return status >= 200 && status != 204 && status != 206 && status != 304 && header(response, "Content-Encoding") == null
            && Gzip.compressible(header(response, "Content-Type"));
    }

    /// Gzips a text body for a client that takes it; a cache hit brings its stored gzip along.
    private static void encode(ContainerResponseContext response, boolean gzipAccepted, RequestState state) {
        Object entity = response.getEntity();
        if (entity == null || entity instanceof byte[] b && b.length == 0 || !compressible(response)) return;
        addVary(response.getHeaders(), "Accept-Encoding");
        if (!gzipAccepted) return;
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

    /// Offers the finished response to the public cache, which serves it without cookies.
    private void offer(ContainerResponseContext response, RoutingContext routing) {
        if (!(response.getEntity() instanceof byte[] body)) {
            if (FrontCache.lifetime(response.getStatus(), header(response, "Cache-Control"), header(response, "Vary")) != null) {
                response.getHeaders().remove("Set-Cookie");
            }
            return;
        }
        var headers = new ArrayList<FrontCache.Header>();
        response.getHeaders().forEach((name, values) -> {
            for (Object value : values) headers.add(new FrontCache.Header(name, String.valueOf(value)));
        });
        if (front.offer(routing, response.getStatus(), headers, body)) response.getHeaders().remove("Set-Cookie");
    }

    /// A weak ETag of the first 32 hex digits of the body's SHA-256 (or its {@link PageDigest} when
    /// it was rendered with cached fragments), for non-empty 200s and 201s that carry no validator
    /// of their own; plus the default `Cache-Control` either way.
    private static void etag(ContainerResponseContext response, byte[] body) {
        boolean tagged = false;
        int status = response.getStatus();
        if ((status == 200 || status == 201) && body.length > 0 && header(response, "ETag") == null && header(response, "Last-Modified") == null) {
            String parts = PageDigest.of(body);
            response.getHeaders().putSingle("ETag", "W/\"" + (parts != null ? parts : sha256Hex(body).substring(0, 32)) + "\"");
            tagged = true;
        }
        if (header(response, "Cache-Control") == null) {
            response.getHeaders().putSingle("Cache-Control", tagged ? "max-age=0, private, must-revalidate" : "no-cache");
        }
    }

    private static String strong(String etag) {
        return etag.startsWith("W/") ? etag.substring(2) : etag;
    }

    /// `If-None-Match` wins over `If-Modified-Since`; weak and strong tags compare equal.
    private static boolean notModified(ContainerResponseContext response, CurrentRequest request) {
        String etag = header(response, "ETag");
        String ifNoneMatch = request.header("if-none-match");
        if (etag != null && ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",")) {
                String c = candidate.strip();
                if (c.equals("*") || c.equals(etag) || strong(c).equals(strong(etag))) return true;
            }
            return false;
        }
        String lastModified = header(response, "Last-Modified");
        return lastModified != null && lastModified.equals(request.header("if-modified-since"));
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
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
