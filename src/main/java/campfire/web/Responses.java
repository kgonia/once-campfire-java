package campfire.web;

import campfire.rails.Json;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;

/// Responses the way the app's Rails controllers send them.
public final class Responses {
    private Responses() {}

    /// `head status`: an empty body that Rails types `text/html` whatever the request asked for.
    /// Being an entity, it also gets the finisher's gzip and no `Cache-Control: no-cache` default,
    /// as in Rails.
    public static Response head(int status) {
        return Response.status(status).entity(new byte[0]).header("Content-Type", "text/html").build();
    }

    /// A 302 to `url`, which must be absolute (`RequestOrigin.url(path)`): Rails resolves
    /// `redirect_to` paths against the request's own scheme and host, which `assume_ssl` makes
    /// `https` behind a TLS-terminating proxy, and JAX-RS's `location()` would use the
    /// connection's instead.
    public static Response redirect(String url) {
        return html(302, new byte[0]).header("Location", url).build();
    }

    public static Response.ResponseBuilder html(int status, String html) {
        return html(status, html.getBytes(StandardCharsets.UTF_8));
    }

    public static Response.ResponseBuilder html(int status, byte[] html) {
        return Response.status(status).entity(html).header("Content-Type", "text/html; charset=utf-8");
    }

    public static Response turboStream(byte[] body) {
        return Response.ok(body).header("Content-Type", "text/vnd.turbo-stream.html; charset=utf-8").build();
    }

    public static Response json(int status, Object value) {
        return Response.status(status).entity(Json.encode(value).getBytes(StandardCharsets.UTF_8))
            .header("Content-Type", "application/json; charset=utf-8").build();
    }
}
