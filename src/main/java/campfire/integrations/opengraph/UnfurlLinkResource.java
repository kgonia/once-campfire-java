package campfire.integrations.opengraph;

import campfire.web.Formats;
import campfire.web.Params;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullMarked;

/// The composer asks for a pasted URL's OpenGraph metadata, to offer a link preview. 204 when the
/// page has none worth showing (or can't be fetched in time).
@Path("/unfurl_link")
@RunOnVirtualThread
@NullMarked
public class UnfurlLinkResource {
    @Inject
    RoutingContext routing;
    @Inject
    Opengraph opengraph;

    @POST
    public Response create(byte[] body) {
        Object url = Params.of(routing.request(), body).get("url");
        if (url == null || url instanceof String s && s.isBlank()) return parameterMissing();
        // A hash or array passes the presence check but isn't a URL.
        if (!(url instanceof String link)) return Formats.head(routing, 204);
        return switch (opengraph.unfurl(link)) {
            case Opengraph.Unfurl.Json json ->
                Response.ok(json.body().getBytes(StandardCharsets.UTF_8)).header("Content-Type", "application/json; charset=utf-8").build();
            case Opengraph.Unfurl.NoContent none -> Formats.head(routing, 204);
        };
    }

    /// Rails' exception page for a missing parameter: there's no `public/400.html`, so it's empty.
    private Response parameterMissing() {
        return Response.status(400).entity(new byte[0]).header("Content-Type", "text/html; charset=UTF-8").build();
    }
}
