package campfire.web;

import campfire.App;
import campfire.view.PageDigest;
import campfire.view.Pages;
import io.quarkus.arc.ClientProxy;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.lang.annotation.Annotation;
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
/// request id and runtime, the version and security headers, the stylesheet preload `Link`, a
/// content ETag (answering 304 when the client has it), offers the page to the response caches,
/// and gzips it for a client that takes gzip (see {@link ContentEncoding}).
///
/// Exception pages carry none of the page headers, as Rails renders them outside the controller.
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
    public void finish(ContainerRequestContext context, ContainerResponseContext response) {
        // Each call through the request-scoped client proxy looks the instance up again.
        CurrentRequest request = ClientProxy.unwrap(current);
        MultivaluedMap<String, Object> headers = response.getHeaders();
        // The resource set the length itself (files, error pages): an empty body stays unencoded.
        boolean lengthSet = response.getHeaderString("Content-Length") != null;
        if (request.isExceptionResponse()) {
            if (request.ssl()) headers.putSingle("strict-transport-security", "max-age=63072000; includeSubDomains");
            requestTracking(headers, request);
            encode(response, true, request);
            offer(response, request);
            return;
        }
        request.commitSession();
        for (String cookie : request.setCookies()) headers.add("Set-Cookie", cookie);
        if (!request.isFramework()) {
            headers.putSingle("X-Version", app.config().appVersion());
            app.config().gitRevision().filter(r -> !r.isEmpty()).ifPresent(r -> headers.putSingle("X-Rev", r));
        }
        if (!request.isDownload()) {
            headers.putSingle("x-frame-options", "SAMEORIGIN");
            headers.putSingle("x-content-type-options", "nosniff");
            headers.putSingle("x-xss-protection", "0");
            headers.putSingle("x-permitted-cross-domain-policies", "none");
            headers.putSingle("referrer-policy", "strict-origin-when-cross-origin");
        }
        if (request.ssl()) headers.putSingle("strict-transport-security", "max-age=63072000; includeSubDomains");
        if (pages.stylesheetsLinked()) linkStylesheets(headers);
        // Turbo Streams are always rendered, and a render whose format came from `Accept` says so.
        String type = header(response, "Content-Type");
        if (type != null && type.startsWith("text/vnd.turbo-stream.html") && header(response, "Vary") == null && Formats.variesOnAccept(request)) {
            headers.putSingle("Vary", "Accept");
        }
        requestTracking(headers, request);
        // Bodiless responses (204, 304) get Rack::ETag's default too.
        if (response.getEntity() == null && header(response, "Cache-Control") == null) headers.putSingle("Cache-Control", "no-cache");
        readSmallFile(response);
        if (response.getEntity() instanceof byte[] body) {
            etag(response, body, !request.isDownload());
            if (response.getStatus() == 200 && request.isGet() && notModified(response, request)) {
                response.setStatus(304);
                response.setEntity(null);
                headers.remove("Content-Type");
                // It still varies as the page it stands for does.
                String vary = ContentEncoding.vary(varyValues(headers));
                if (vary != null) headers.putSingle("Vary", vary);
                return;
            }
            boolean gzipAccepted = "gzip".equals(ContentEncoding.negotiate(request.header("accept-encoding")));
            if (request.cacheTicket() != null) {
                byte[] cached = request.cachedGzip();
                byte[] gzip = cached != null ? cached : gzipAccepted ? Gzip.compress(body) : null;
                cache.admit(request, response.getStatus(), liveHeaders(response), body, gzip);
                request.cachedGzip(gzip);
            }
        }
        encode(response, lengthSet, request);
        offer(response, request);
    }

    /// Rails' request id and runtime, for every response that isn't a static file.
    private static void requestTracking(MultivaluedMap<String, Object> headers, CurrentRequest request) {
        headers.putSingle("x-request-id", Front.requestId(request.header("x-request-id")));
        headers.putSingle("x-runtime", Front.runtime(request.routing()));
    }

    /// Appends the stylesheet preloads to any `Link` the page set itself.
    private void linkStylesheets(MultivaluedMap<String, Object> headers) {
        String links = app.assets().stylesheetLinkHeader();
        Object existing = headers.getFirst("Link");
        headers.putSingle("Link", existing == null || existing.toString().isEmpty() ? links : existing + "," + links);
    }

    /// Avatars, logos and icons go out whole, which lets the caches keep them; large files stream.
    private void readSmallFile(ContainerResponseContext response) {
        if (!(response.getEntity() instanceof Path file)) return;
        try {
            if (Files.size(file) <= front.config().maxCacheItemSize()) response.setEntity(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// The app's gzip, then the proxy's for what's still unencoded.
    private void encode(ContainerResponseContext response, boolean lengthSet, CurrentRequest request) {
        MultivaluedMap<String, Object> headers = response.getHeaders();
        Object entity = response.getEntity();
        boolean empty = entity == null || entity instanceof byte[] b && b.length == 0;
        if (ContentEncoding.shouldDeflate(response.getStatus(), header(response, "Cache-Control"), header(response, "Content-Encoding"),
            lengthSet && empty)) {
            String encoding = ContentEncoding.negotiate(request.header("accept-encoding"));
            if (encoding == null) {
                headers.clear();
                byte[] body = ContentEncoding.notAcceptable(request.path() + (request.query() == null ? "" : "?" + request.query()));
                response.setStatus(406);
                response.setEntity(body, new Annotation[0], MediaType.TEXT_PLAIN_TYPE);
                headers.putSingle("Content-Length", body.length);
                return;
            }
            String merged = ContentEncoding.vary(varyValues(headers));
            if (merged != null) headers.putSingle("Vary", merged);
            if (encoding.equals("gzip")) {
                long mtime = ContentEncoding.mtime(header(response, "Last-Modified"));
                headers.putSingle("Content-Encoding", "gzip");
                headers.remove("Content-Length");
                response.setEntity(gzipped(entity, mtime, request));
                return;
            }
        }
        compressUnencoded(response, request);
    }

    private static Object gzipped(@Nullable Object entity, long mtime, CurrentRequest request) {
        return switch (entity) {
            case null -> Gzip.compress(new byte[0], mtime, null);
            case byte[] body when mtime == 0 && request.cachedGzip() != null -> request.cachedGzip();
            case byte[] body -> Gzip.compress(body, mtime, null);
            case String text -> Gzip.compress(text.getBytes(StandardCharsets.UTF_8), mtime, null);
            case Path file -> (StreamingOutput) out -> {
                try (var gzip = Gzip.stream(out, mtime, null)) {
                    Files.copy(file, gzip);
                }
            };
            case StreamingOutput body -> (StreamingOutput) out -> {
                try (var gzip = Gzip.stream(out, mtime, null)) {
                    body.write(gzip);
                }
            };
            default -> throw new IllegalStateException("cannot gzip a " + entity.getClass().getName());
        };
    }

    /// Bodies the app left unencoded that the client still wants compressed (by its qualities the
    /// proxy reads gzip as wanted where the app's negotiation picked identity).
    private void compressUnencoded(ContainerResponseContext response, CurrentRequest request) {
        if (!front.config().compression() || !(response.getEntity() instanceof byte[] body) || response.getStatus() < 200) return;
        FrontCompression compression = front.compression();
        var encoding = FrontCompression.select(request.isHead(), request.header("accept-encoding"));
        if (!compression.wanted(encoding, request.header("cookie"), request.header("authorization"), request.header("x-csrf-token"),
            header(response, "Set-Cookie"), header(response, "Cache-Control"), header(response, "Vary"), header(response, "Content-Encoding"),
            header(response, "Content-Range"), header(response, "Content-Type"), body.length)) {
            return;
        }
        response.getHeaders().putSingle("Content-Encoding", "gzip");
        response.getHeaders().remove("Content-Length");
        response.getHeaders().remove("Accept-Ranges");
        response.setEntity(Gzip.compress(body, 0, compression.jitter(body)));
    }

    /// Offers the finished response to the proxy's cache; a public one loses its cookies.
    private void offer(ContainerResponseContext response, CurrentRequest request) {
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
        if (front.offer(request.routing(), response.getStatus(), headers, body)) response.getHeaders().remove("Set-Cookie");
    }

    /// A weak ETag of the first 32 hex digits of the body's SHA-256 (or its {@link PageDigest} when
    /// it was rendered with cached fragments), for non-empty 200s and 201s that carry no validator
    /// of their own; plus the default `Cache-Control` either way. Downloads
    /// are never digested (Rails streams them).
    private static void etag(ContainerResponseContext response, byte[] body, boolean digest) {
        boolean tagged = false;
        int status = response.getStatus();
        if (digest && (status == 200 || status == 201) && body.length > 0 && header(response, "ETag") == null && header(response, "Last-Modified") == null) {
            String parts = PageDigest.of(body);
            response.getHeaders().putSingle("ETag", "W/\"" + (parts != null ? parts : sha256Hex(body).substring(0, 32)) + "\"");
            tagged = true;
        }
        if (header(response, "Cache-Control") == null) {
            response.getHeaders().putSingle("Cache-Control", tagged ? "max-age=0, private, must-revalidate" : "no-cache");
        }
    }

    /// `If-None-Match` wins over `If-Modified-Since`; weak and strong tags compare equal.
    private static boolean notModified(ContainerResponseContext response, CurrentRequest request) {
        String etag = header(response, "ETag");
        String ifNoneMatch = request.header("if-none-match");
        if (etag != null && ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",")) {
                String c = candidate.strip();
                if (c.equals("*") || c.equals(etag) || c.replaceFirst("^W/", "").equals(etag.replaceFirst("^W/", ""))) return true;
            }
            return false;
        }
        String lastModified = header(response, "Last-Modified");
        return lastModified != null && lastModified.equals(request.header("if-modified-since"));
    }

    private static List<String> varyValues(MultivaluedMap<String, Object> headers) {
        var values = new ArrayList<String>();
        for (Object value : headers.getOrDefault("Vary", List.of())) values.add(String.valueOf(value));
        return values;
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
