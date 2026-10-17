package campfire.storage;

import campfire.rails.SecureRandoms;
import campfire.assets.Assets;
import campfire.rails.HttpFormats;
import campfire.rails.Json;
import campfire.sessions.Authentication;
import campfire.web.Access;
import campfire.web.AuthenticityToken;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Active Storage's own endpoints. Blob and representation URLs are signed and public: the
/// redirect ones send the browser to a five-minute disk URL, the proxy ones stream the file
/// themselves under a cache-forever URL. The disk PUT and direct uploads need a session cookie.
/// These are Rails' controllers, not the app's, so the proxies (`ActionController::Live`) skip
/// even the default security headers, and failures render Rails' bare exception pages.
@Path("/rails/active_storage")
@Access.Framework
@RunOnVirtualThread
@NullMarked
public class ActiveStorageResource {
    /// `expires_in 100.years`
    private static final long HUNDRED_YEARS = 3_155_695_200L;
    /// `http_cache_forever` pins Last-Modified to 2011-01-01.
    private static final String FOREVER_LAST_MODIFIED = "Sat, 01 Jan 2011 00:00:00 GMT";
    private static final String DISK_CACHE_CONTROL = "max-age=3600, public";

    @Inject
    CurrentRequest request;
    @Inject
    ActiveStorageService storage;
    @Inject
    Authentication authentication;
    @Inject
    Assets assets;

    @GET
    @Path("blobs/redirect/{signed_id}/{filename: .+}")
    public Response blobRedirect(@PathParam("signed_id") String signedId) {
        return storage.blob(signedId).map(this::redirectTo).orElseGet(() -> request.head(404));
    }

    /// The route `rails_blob_path` used before the redirect/proxy split.
    @GET
    @Path("blobs/{signed_id}/{filename: .+}")
    public Response blob(@PathParam("signed_id") String signedId) {
        return blobRedirect(signedId);
    }

    @GET
    @Path("blobs/proxy/{signed_id}/{filename: .+}")
    public Response blobProxy(@PathParam("signed_id") String signedId) {
        request.markDownload();
        Blob blob = storage.blob(signedId).orElse(null);
        if (blob == null) return request.head(404);
        String range = request.header("range");
        if (range != null && !range.isBlank()) return byteRange(blob, range);
        return stream(blob, true);
    }

    @GET
    @Path("representations/redirect/{signed_blob_id}/{variation_key}/{filename: .+}")
    public Response representationRedirect(@PathParam("signed_blob_id") String signedBlobId, @PathParam("variation_key") String variationKey) {
        Blob blob = storage.blob(signedBlobId).orElse(null);
        if (blob == null) return request.head(404);
        return storage.representation(blob, variationKey).map(this::redirectTo).orElseGet(() -> request.head(404));
    }

    @GET
    @Path("representations/{signed_blob_id}/{variation_key}/{filename: .+}")
    public Response representation(@PathParam("signed_blob_id") String signedBlobId, @PathParam("variation_key") String variationKey) {
        return representationRedirect(signedBlobId, variationKey);
    }

    @GET
    @Path("representations/proxy/{signed_blob_id}/{variation_key}/{filename: .+}")
    public Response representationProxy(@PathParam("signed_blob_id") String signedBlobId, @PathParam("variation_key") String variationKey) {
        request.markDownload();
        Blob blob = storage.blob(signedBlobId).orElse(null);
        if (blob == null) return request.head(404);
        return storage.representation(blob, variationKey).map(image -> stream(image, false)).orElseGet(() -> request.head(404));
    }

    /// The disk service's file, with conditional GET on its mtime and byte ranges.
    @GET
    @Path("disk/{encoded_key}/{filename: .+}")
    public Response diskShow(@PathParam("encoded_key") String encodedKey) {
        Disk.Key key = storage.diskKey(encodedKey).orElse(null);
        if (key == null) return withHeader(Formats.head(request, 404), "Cache-Control", DISK_CACHE_CONTROL);
        var reply = new FileServer.Reply();
        try {
            FileServer.serve(reply, request.method(), request.header("range"), request.header("if-modified-since"), storage.pathFor(key.key()),
                key.contentType(), key.disposition());
        } catch (NoSuchFileException e) {
            return withHeader(Formats.head(request, 404), "Cache-Control", DISK_CACHE_CONTROL);
        }
        var response = Response.status(reply.status).entity(reply.file != null ? reply.file : reply.body);
        reply.headers.forEach(response::header);
        return response.header("Cache-Control", DISK_CACHE_CONTROL).build();
    }

    /// A direct upload's bytes, which must be exactly the length and type the token was signed
    /// for (and match its checksum).
    @PUT
    @Path("disk/{encoded_token}")
    public Response diskUpdate(@PathParam("encoded_token") String encodedToken) {
        if (!authentication.hasSession(request)) return Formats.head(request, 401);
        Disk.Token token = storage.uploadToken(encodedToken).orElse(null);
        if (token == null) return Formats.head(request, 404);
        byte[] body = request.body();
        if (!acceptable(token, body) || !storage.upload(token, body)) return Formats.head(request, 422);
        return Formats.head(request, 204);
    }

    /// Creates the blob a client then PUTs to the returned URL. Unlike the app's own pages, this
    /// needs a CSRF token.
    @POST
    @Path("direct_uploads")
    public Response directUploadsCreate() {
        if (!AuthenticityToken.verified(request)) return exceptionPage(422);
        if (!authentication.hasSession(request)) return Formats.head(request, 401);
        Map<?, ?> params = blobParams();
        if (params == null) return exceptionPage(400);
        String filename = text(params.get("filename"));
        String checksum = text(params.get("checksum"));
        if (filename == null || filename.isEmpty() || checksum == null || checksum.isEmpty()) return exceptionPage(422);
        Long byteSize = integer(params.get("byte_size"));
        if (byteSize == null) return exceptionPage(422);
        if (byteSize < 0 || byteSize > storage.maxDirectUploadBytes()) return exceptionPage(413);
        Map<?, ?> metadata = params.get("metadata") instanceof Map<?, ?> m ? m : Map.of();
        var upload = storage.createDirectUpload(request.baseUrl(), filename, text(params.get("content_type")), metadata, byteSize, checksum);
        Response json = CurrentRequest.json(200, upload.json());
        return Formats.variesOnAccept(request) ? withHeader(json, "Vary", "Accept") : json;
    }

    // -- Responses ---------------------------------------------------------------------------------

    /// A private, five-minute redirect to the blob's disk URL.
    private Response redirectTo(Blob blob) {
        String url = storage.serviceUrl(request.baseUrl(), blob, request.params().string("disposition"));
        return Response.fromResponse(request.redirect(url)).header("Cache-Control", "max-age=300, private").header("Date", now()).build();
    }

    /// The whole file under a URL cached for a hundred years; the ETag is the URL's own.
    private Response stream(Blob blob, boolean acceptRanges) {
        String etag = "W/\"" + sha256Hex(fullPath() + (isTurboFrame() ? "/frame" : "")).substring(0, 32) + "\"";
        var validators = Map.of("ETag", etag, "Last-Modified", FOREVER_LAST_MODIFIED, "Date", now());
        if (fresh(etag)) {
            return withHeaders(Response.status(304), validators)
                .header("Cache-Control", "max-age=" + HUNDRED_YEARS + ", public, immutable").build();
        }
        var file = storage.file(blob).orElse(null);
        if (file == null) return withHeaders(Response.fromResponse(request.head(404)), validators).header("Cache-Control", "no-cache").build();
        var response = withHeaders(Response.ok(file), validators)
            .header("Cache-Control", "max-age=" + HUNDRED_YEARS + ", public, immutable")
            .header("Content-Type", servingType(blob))
            .header("Content-Disposition", disposition(blob, request.params().string("disposition")));
        if (acceptRanges) response.header("Accept-Ranges", "bytes");
        return response.build();
    }

    /// One range as the bytes themselves, several as `multipart/byteranges`; a header that
    /// names no satisfiable range (or none at all) is a 416.
    private Response byteRange(Blob blob, String header) {
        List<long[]> ranges = FileServer.byteRanges(header, Math.max(0, blob.byteSize()));
        if (ranges == null || ranges.isEmpty()) return request.head(416);
        var file = storage.file(blob).orElseThrow(() -> new StorageException("file not found for blob " + blob.id()));
        String type = servingType(blob);
        var parts = new ArrayList<Object>();
        String contentType;
        String contentRange = null;
        if (ranges.size() == 1) {
            parts.add(ranges.getFirst());
            contentType = type;
            contentRange = "bytes " + ranges.getFirst()[0] + "-" + ranges.getFirst()[1] + "/" + blob.byteSize();
        } else {
            String boundary = HexFormat.of().formatHex(randomBytes());
            for (long[] range : ranges) {
                parts.add(("\r\n--" + boundary + "\r\nContent-Type: " + type + "\r\nContent-Range: bytes " + range[0] + "-" + range[1] + "/"
                    + blob.byteSize() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                parts.add(range);
            }
            parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            contentType = "multipart/byteranges; boundary=" + boundary;
        }
        long length = parts.stream().mapToLong(p -> p instanceof long[] r ? r[1] - r[0] + 1 : ((byte[]) p).length).sum();
        StreamingOutput body = out -> {
            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                for (Object part : parts) {
                    if (part instanceof long[] r) copy(channel, r[0], r[1] - r[0] + 1, out);
                    else out.write((byte[]) part);
                }
            }
        };
        var response = Response.status(206).entity(body).header("Content-Type", contentType)
            .header("Content-Disposition", disposition(blob, null)).header("Content-Length", length).header("Accept-Ranges", "bytes")
            .header("Cache-Control", "no-cache");
        if (contentRange != null) response.header("Content-Range", contentRange);
        return response.build();
    }

    /// What Rails' exception pages answer: `public/<status>.html`, or nothing when there's none.
    private Response exceptionPage(int status) {
        request.markExceptionResponse();
        var page = assets.file("/" + status + ".html");
        return Response.status(status).entity(page == null ? new byte[0] : page.body()).header("Content-Type", "text/html; charset=UTF-8").build();
    }

    // -- Request details ---------------------------------------------------------------------------

    /// `params.expect(blob: [...])` from a JSON body (what Active Storage's JavaScript sends) or
    /// form parameters; null when there's no `blob` hash.
    private @Nullable Map<?, ?> blobParams() {
        String type = request.header("content-type");
        byte[] body = request.body();
        if (type != null && type.toLowerCase(Locale.ROOT).contains("json") && body.length > 0) {
            Object parsed = Json.parseOrNull(new String(body, StandardCharsets.UTF_8));
            return parsed instanceof Map<?, ?> m && m.get("blob") instanceof Map<?, ?> blob ? blob : null;
        }
        return request.params().get("blob") instanceof Map<?, ?> blob ? blob : null;
    }

    /// The token's type and length against the request's media type and `Content-Length` (the
    /// body's own length when sent chunked).
    private boolean acceptable(Disk.Token token, byte[] body) {
        String header = request.header("content-type");
        String mediaType = null;
        if (header != null) {
            int semicolon = header.indexOf(';');
            String type = (semicolon < 0 ? header : header.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
            if (!type.isEmpty()) mediaType = type;
        }
        String expected = token.contentType() == null ? null : token.contentType().toLowerCase(Locale.ROOT);
        String contentLength = request.header("content-length");
        long length = request.header("transfer-encoding") != null ? body.length : FileServer.toI(contentLength == null ? "" : contentLength);
        return java.util.Objects.equals(expected, mediaType) && token.contentLength() == length;
    }

    /// `request.fresh?`: an `If-None-Match` naming the ETag (or `*`), else an `If-Modified-Since`
    /// no earlier than the pinned Last-Modified.
    private boolean fresh(String etag) {
        String ifNoneMatch = request.header("if-none-match");
        if (ifNoneMatch != null) {
            for (String candidate : ifNoneMatch.split(",")) if (candidate.strip().equals(etag) || candidate.strip().equals("*")) return true;
            return false;
        }
        String since = request.header("if-modified-since");
        if (since == null) return false;
        try {
            return !ZonedDateTime.parse(since, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().isBefore(Instant.parse("2011-01-01T00:00:00Z"));
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private String fullPath() {
        String query = request.query();
        return request.path() + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    private boolean isTurboFrame() {
        String frame = request.header("turbo-frame");
        return frame != null && !frame.isBlank();
    }

    private static String servingType(Blob blob) {
        String type = ContentTypes.forServing(blob.contentType());
        return type == null ? ContentTypes.BINARY : type;
    }

    /// Inline unless the type must download or `requested` says otherwise.
    private static String disposition(Blob blob, @Nullable String requested) {
        String forced = ContentTypes.forcedDisposition(blob.contentType());
        String disposition = forced != null ? forced : requested != null ? requested : "inline";
        return HttpFormats.contentDisposition(disposition, new Filename(blob.filename()).sanitized());
    }

    private static @Nullable String text(@Nullable Object value) {
        return switch (value) {
            case String s -> s;
            case Long l -> l.toString();
            case Integer i -> i.toString();
            case null, default -> null;
        };
    }

    /// Stricter than Rails' integer cast (which makes junk 0): a byte size must be a number.
    private static @Nullable Long integer(@Nullable Object value) {
        return switch (value) {
            case Long l -> l;
            case Integer i -> (long) i;
            case String s when s.strip().matches("[+-]?\\d{1,18}") -> Long.parseLong(s.strip().replace("+", ""));
            case null, default -> null;
        };
    }

    private static void copy(FileChannel channel, long position, long count, OutputStream out) throws IOException {
        var target = Channels.newChannel(out);
        long done = 0;
        while (done < count) {
            long sent = channel.transferTo(position + done, count - done, target);
            if (sent <= 0) break;
            done += sent;
        }
    }

    private static byte[] randomBytes() {
        return SecureRandoms.bytes(16);
    }

    private static String now() {
        return CurrentRequest.httpDate(Instant.now());
    }

    private static Response withHeader(Response response, String name, String value) {
        return Response.fromResponse(response).header(name, value).build();
    }

    private static Response.ResponseBuilder withHeaders(Response.ResponseBuilder response, Map<String, String> headers) {
        headers.forEach(response::header);
        return response;
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
