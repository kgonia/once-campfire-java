package campfire.web;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Conditional GET and caching headers for actions that validate their own responses (Rails'
/// `fresh_when`, `expires_in`, `send_file`), rather than leaving it to the body digest
/// {@link ResponseFinisher} computes.
@NullMarked
public final class HttpCache {
    private HttpCache() {}

    private static final DateTimeFormatter MICROSECONDS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSSSS").withZone(ZoneOffset.UTC);

    /// A record's cache version: `users/1-20240601120000000000` (updated_at to the microsecond).
    public static String cacheKeyWithVersion(String table, long id, Instant updatedAt) {
        return table + "/" + id + "-" + MICROSECONDS.format(updatedAt);
    }

    /// The weak ETag of `validator` combined with what else a page varies on: being a Turbo-Frame
    /// request, the template's digest, and the flash.
    public static String weakEtag(CurrentRequest request, @Nullable String validator, @Nullable String templateDigest) {
        var parts = new ArrayList<String>();
        if (validator != null) parts.add(validator);
        String frame = request.header("turbo-frame");
        if (frame != null && !frame.isBlank()) parts.add("frame");
        if (templateDigest != null) parts.add(templateDigest);
        var flash = request.flash();
        var flashes = new ArrayList<String>();
        String notice = flash.get("notice");
        String alert = flash.get("alert");
        if (notice != null) flashes.add("notice=Some(String(" + campfire.rails.Json.encode(notice) + "))");
        if (alert != null) flashes.add("alert=Some(String(" + campfire.rails.Json.encode(alert) + "))");
        if (!flashes.isEmpty()) parts.add(String.join("&", flashes));
        return "W/\"" + sha256Hex(String.join("/", parts)).substring(0, 32) + "\"";
    }

    /// The client already has `etag`: `If-None-Match` lists it, or is `*`.
    public static boolean fresh(CurrentRequest request, String etag) {
        String ifNoneMatch = request.header("if-none-match");
        if (ifNoneMatch == null) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.strip();
            if (tag.equals(etag) || tag.equals("*")) return true;
        }
        return false;
    }

    public static Response notModified(String etag) {
        return Response.status(304).header("ETag", etag).header("Cache-Control", "max-age=0, private, must-revalidate").build();
    }

    /// `response` validated by `etag`, with the `Cache-Control` that makes clients revalidate it.
    public static Response.ResponseBuilder tagged(Response.ResponseBuilder response, String etag) {
        return response.header("ETag", etag).header("Cache-Control", "max-age=0, private, must-revalidate");
    }

    /// `Cache-Control` for `seconds`, and the `Date` it counts from.
    public static Response.ResponseBuilder expiresIn(Response.ResponseBuilder response, long seconds, boolean isPublic,
                                                     @Nullable Long staleWhileRevalidate) {
        var value = new StringBuilder("max-age=").append(seconds).append(isPublic ? ", public" : ", private");
        if (staleWhileRevalidate != null) value.append(", stale-while-revalidate=").append(staleWhileRevalidate);
        return response.header("Cache-Control", value.toString()).header("Date", CurrentRequest.httpDate(Instant.now()));
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
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
