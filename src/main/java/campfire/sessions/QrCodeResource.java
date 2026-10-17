package campfire.sessions;

import campfire.integrations.qr.QrCode;
import campfire.rails.Base64s;
import campfire.web.Access;
import campfire.web.HttpCache;
import campfire.web.HttpError;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullMarked;

/// A QR code SVG of a URL, given Base64url-encoded in the path (the transfer link's QR code).
/// The same id is always the same image, so it's cacheable publicly for a year.
@Path("/qr_code/{id}")
@RunOnVirtualThread
@Access.Public
@NullMarked
public class QrCodeResource {
    static final long ONE_YEAR_SECONDS = 31_556_952;

    /// Malformed Base64 is a 500; a URL too long for any QR code a 422.
    @GET
    public Response show(@PathParam("id") String id) {
        byte[] url = Base64s.urlsafeDecode(id);
        if (url == null) throw new IllegalArgumentException("invalid base64");
        String svg = QrCode.svg(url);
        if (svg == null) throw new HttpError(422);
        var response = Response.ok(svg.getBytes(StandardCharsets.UTF_8)).header("Content-Type", "image/svg+xml; charset=utf-8");
        return HttpCache.expiresIn(response, ONE_YEAR_SECONDS, true, null).build();
    }
}
