package campfire.storage;

import campfire.assets.Assets;
import campfire.rails.HttpFormats;
import campfire.rails.Json;
import campfire.rails.RubyStrings;
import campfire.sessions.Authentication;
import campfire.web.Access;
import campfire.web.AccessFilter;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Responses;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import org.jboss.resteasy.reactive.PathPart;
import org.jboss.resteasy.reactive.common.util.DateUtil;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Active Storage's own endpoints. Blob and representation URLs are signed and public: the
/// redirect ones send the browser to a five-minute disk URL, the proxy ones stream the file
/// themselves under a cache-forever URL. The disk PUT and direct uploads need a session cookie.
@Path("/rails/active_storage")
@Access.Framework
@RunOnVirtualThread
@NullMarked
public class ActiveStorageResource {
    /// `expires_in 100.years`
    private static final long HUNDRED_YEARS = 3_155_695_200L;
    /// `http_cache_forever` pins Last-Modified to 2011-01-01.
    private static final Instant FOREVER_LAST_MODIFIED = Instant.parse("2011-01-01T00:00:00Z");
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
        return storage.blob(signedId).map(this::redirectTo).orElseGet(() -> Responses.head(404));
    }

    /// The route `rails_blob_path` used before the redirect/proxy split.
    @GET
    @Path("blobs/{signed_id}/{filename: .+}")
    public Response blob(@PathParam("signed_id") String signedId) {
        return blobRedirect(signedId);
    }

    @GET
    @Path("blobs/proxy/{signed_id}/{filename: .+}")
    public Response blobProxy(@PathParam("signed_id") String signedId, @Context Request http) {
        Blob blob = storage.blob(signedId).orElse(null);
        if (blob == null) return Responses.head(404);
        long size = Math.max(0, blob.byteSize());
        ByteRange range = ByteRange.parse(request.header("range"), size);
        if (range == null) return stream(http, blob, true);
        if (!range.satisfiable()) return unsatisfiable(size).build();
        var file = storage.file(blob).orElseThrow(() -> new StorageException("file not found for blob " + blob.id()));
        return partial(file, range, size).header("Content-Type", servingType(blob)).header("Content-Disposition", disposition(blob, null))
            .header("Cache-Control", "no-cache").build();
    }

    @GET
    @Path("representations/redirect/{signed_blob_id}/{variation_key}/{filename: .+}")
    public Response representationRedirect(@PathParam("signed_blob_id") String signedBlobId, @PathParam("variation_key") String variationKey) {
        Blob blob = storage.blob(signedBlobId).orElse(null);
        if (blob == null) return Responses.head(404);
        return storage.representation(blob, variationKey).map(this::redirectTo).orElseGet(() -> Responses.head(404));
    }

    @GET
    @Path("representations/{signed_blob_id}/{variation_key}/{filename: .+}")
    public Response representation(@PathParam("signed_blob_id") String signedBlobId, @PathParam("variation_key") String variationKey) {
        return representationRedirect(signedBlobId, variationKey);
    }

    @GET
    @Path("representations/proxy/{signed_blob_id}/{variation_key}/{filename: .+}")
    public Response representationProxy(@PathParam("signed_blob_id") String signedBlobId, @PathParam("variation_key") String variationKey,
                                        @Context Request http) {
        Blob blob = storage.blob(signedBlobId).orElse(null);
        if (blob == null) return Responses.head(404);
        return storage.representation(blob, variationKey).map(image -> stream(http, image, false)).orElseGet(() -> Responses.head(404));
    }

    /// The disk service's file (`Rack::Files`), with conditional GET on its mtime and byte ranges.
    @GET
    @Path("disk/{encoded_key}/{filename: .+}")
    public Response diskShow(@PathParam("encoded_key") String encodedKey, @Context Request http) {
        Disk.Key key = storage.diskKey(encodedKey).orElse(null);
        if (key == null) return withHeader(Formats.head(request, 404), "Cache-Control", DISK_CACHE_CONTROL);
        java.nio.file.Path file = storage.pathFor(key.key());
        long size;
        Instant modified;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toInstant();
        } catch (NoSuchFileException e) {
            return withHeader(Formats.head(request, 404), "Cache-Control", DISK_CACHE_CONTROL);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Response.ResponseBuilder response = preconditions(http, modified, null);
        if (response == null) {
            ByteRange range = ByteRange.parse(request.header("range"), size);
            response = range == null ? Response.ok(file) : range.satisfiable() ? partial(file, range, size) : unsatisfiable(size);
            response.header("Last-Modified", CurrentRequest.httpDate(modified))
                .header("Content-Type", key.contentType() == null ? ContentTypes.BINARY : key.contentType())
                .header("Content-Disposition", key.disposition() == null ? "attachment" : key.disposition());
        }
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

    /// Creates the blob a client then PUTs to the returned URL. Rails guards this with its CSRF
    /// token, which the app's pages don't carry; it gets the same Fetch Metadata check as every
    /// other write instead, so Active Storage's JavaScript can call it from any signed-in page.
    @POST
    @Path("direct_uploads")
    public Response directUploadsCreate() {
        if (!AccessFilter.sameOriginWrite(request)) return exceptionPage(422);
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
        Response json = Responses.json(200, upload.json());
        return Formats.variesOnAccept(request) ? withHeader(json, "Vary", "Accept") : json;
    }

    // -- Responses ---------------------------------------------------------------------------------

    /// A private, five-minute redirect to the blob's disk URL.
    private Response redirectTo(Blob blob) {
        String url = storage.serviceUrl(request.baseUrl(), blob, request.params().string("disposition"));
        return Response.fromResponse(Responses.redirect(url)).header("Cache-Control", "max-age=300, private").header("Date", now()).build();
    }

    /// The whole file under a URL cached for a hundred years; the ETag is the URL's own.
    private Response stream(Request http, Blob blob, boolean acceptRanges) {
        String etag = "W/\"" + sha256Hex(fullPath() + (isTurboFrame() ? "/frame" : "")).substring(0, 32) + "\"";
        Response.ResponseBuilder response = preconditions(http, FOREVER_LAST_MODIFIED, EntityTag.valueOf(etag));
        if (response == null) {
            var file = storage.file(blob).orElse(null);
            if (file == null) return validators(Response.fromResponse(Responses.head(404)), etag).header("Cache-Control", "no-cache").build();
            response = validators(Response.ok(file), etag).header("Content-Type", servingType(blob))
                .header("Content-Disposition", disposition(blob, request.params().string("disposition")));
            if (acceptRanges) response.header("Accept-Ranges", "bytes");
        } else {
            response.header("Last-Modified", CurrentRequest.httpDate(FOREVER_LAST_MODIFIED)).header("Date", now());
        }
        return response.header("Cache-Control", "max-age=" + HUNDRED_YEARS + ", public, immutable").build();
    }

    /// Validators as strings: small bodies go to the front cache, which copies headers by `toString`.
    private static Response.ResponseBuilder validators(Response.ResponseBuilder response, String etag) {
        return response.header("ETag", etag).header("Last-Modified", CurrentRequest.httpDate(FOREVER_LAST_MODIFIED)).header("Date", now());
    }

    /// `Request#evaluatePreconditions`: the 304 (or 412) to send instead, or null to send the file.
    /// A validator that doesn't parse, such as a garbage `If-Modified-Since`, is no condition.
    private static Response.@Nullable ResponseBuilder preconditions(Request http, Instant lastModified, @Nullable EntityTag etag) {
        try {
            return etag == null ? http.evaluatePreconditions(Date.from(lastModified)) : http.evaluatePreconditions(Date.from(lastModified), etag);
        } catch (IllegalArgumentException | DateUtil.DateParseException e) {
            return null;
        }
    }

    /// The range's bytes, sent from the file without reading them in. A 206 is never gzipped (the
    /// finisher skips it), so the length and `Content-Range` count the stored bytes.
    private static Response.ResponseBuilder partial(java.nio.file.Path file, ByteRange range, long size) {
        return Response.status(206).entity(new PathPart(file, range.first(), range.length())).header("Content-Range", range.contentRange(size))
            .header("Content-Length", range.length()).header("Accept-Ranges", "bytes");
    }

    private static Response.ResponseBuilder unsatisfiable(long size) {
        return Response.status(416).entity(new byte[0]).header("Content-Range", "bytes */" + size).header("Content-Length", 0);
    }

    /// What Rails' exception pages answer: `public/<status>.html`, or nothing when there's none.
    private Response exceptionPage(int status) {
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
        long length = request.header("transfer-encoding") != null ? body.length : RubyStrings.toI(contentLength == null ? "" : contentLength);
        return java.util.Objects.equals(expected, mediaType) && token.contentLength() == length;
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

    private static String now() {
        return CurrentRequest.httpDate(Instant.now());
    }

    private static Response withHeader(Response response, String name, String value) {
        return Response.fromResponse(response).header(name, value).build();
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
