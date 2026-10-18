package campfire.web;

import campfire.view.PageDigest;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Conditional GET and caching headers for actions that validate their own responses (Rails'
/// `fresh_when`, `expires_in`, `send_file`), rather than leaving it to the body digest
/// {@link ResponseFinisher} computes.
@NullMarked
public final class HttpCache {
    private HttpCache() {}

    private static final DateTimeFormatter MICROSECONDS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSSSS").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC);

    /// RFC 9110 dates with a two-digit day, as Rack writes them (`RFC_1123_DATE_TIME` doesn't pad).
    public static String httpDate(Instant time) {
        return HTTP_DATE.format(time);
    }

    /// A record's cache version: `users/1-20240601120000000000` (updated_at to the microsecond).
    public static String cacheKeyWithVersion(String table, long id, Instant updatedAt) {
        return table + "/" + id + "-" + MICROSECONDS.format(updatedAt);
    }

    /// `max-age=0, private, must-revalidate`, what Rails sends with an ETag.
    static final CacheControl REVALIDATE = revalidate();

    private static CacheControl revalidate() {
        var control = new CacheControl();
        control.setNoTransform(false);
        control.setMaxAge(0);
        control.setPrivate(true);
        control.setMustRevalidate(true);
        return control;
    }

    /// The weak ETag of `validator` combined with what else a page varies on: being a Turbo-Frame
    /// request, the template's digest, and the flash.
    public static EntityTag etag(@Nullable String turboFrame, RailsSession.Flash flash, @Nullable String validator,
                                 @Nullable String templateDigest) {
        var parts = new ArrayList<String>();
        if (validator != null) parts.add(validator);
        if (turboFrame != null && !turboFrame.isBlank()) parts.add("frame");
        if (templateDigest != null) parts.add(templateDigest);
        for (String key : List.of("notice", "alert")) {
            String message = flash.get(key);
            if (message != null) parts.add(key + "=" + message);
        }
        return new EntityTag(sha256Hex(String.join("/", parts)).substring(0, 32), true);
    }

    /// The 304 for a client that already has `etag`, or null.
    public static @Nullable Response notModified(Request request, EntityTag etag) {
        Response.ResponseBuilder notModified = request.evaluatePreconditions(etag);
        return notModified == null ? null : notModified.cacheControl(REVALIDATE).build();
    }

    /// `response` validated by `etag`, with the `Cache-Control` that makes clients revalidate it.
    public static Response.ResponseBuilder tagged(Response.ResponseBuilder response, EntityTag etag) {
        return response.tag(etag).cacheControl(REVALIDATE);
    }

    /// `Cache-Control` for `seconds`, and the `Date` it counts from. Written out: {@link CacheControl}
    /// quotes extension values, and browsers ignore a quoted `stale-while-revalidate`.
    public static Response.ResponseBuilder expiresIn(Response.ResponseBuilder response, long seconds, boolean isPublic,
                                                     @Nullable Long staleWhileRevalidate) {
        var value = new StringBuilder("max-age=").append(seconds).append(isPublic ? ", public" : ", private");
        if (staleWhileRevalidate != null) value.append(", stale-while-revalidate=").append(staleWhileRevalidate);
        return response.header("Cache-Control", value.toString()).header("Date", HttpCache.httpDate(Instant.now()));
    }

    /// A file shown inline under its own name. Read whole: these are avatars and icons.
    public static Response.ResponseBuilder sendFile(Path path, String contentType) {
        byte[] body;
        try {
            body = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read file " + path, e);
        }
        return inline(body, path.getFileName().toString(), contentType);
    }

    /// Data sent as a file named `filename` (plain ASCII), shown inline.
    public static Response.ResponseBuilder inline(byte[] body, String filename, String contentType) {
        return Response.ok(body).header("Content-Type", contentType)
            .header("Content-Disposition", "inline; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
            .header("Content-Transfer-Encoding", "binary");
    }

    private static String sha256Hex(String text) {
        return HexFormat.of().formatHex(PageDigest.sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
