package campfire.integrations.opengraph;

import campfire.integrations.net.Network;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import io.smallrye.common.annotation.RunOnVirtualThread;
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
    CurrentRequest request;
    @Inject
    Network net;

    @POST
    public Response create() {
        Object url = request.params().get("url");
        if (url == null || url instanceof String s && s.isBlank()) return parameterMissing();
        // A hash or array passes the presence check but isn't a URL.
        if (!(url instanceof String link)) return Formats.head(request, 204);
        return switch (Opengraph.unfurl(net, link)) {
            case Opengraph.Unfurl.Json json -> {
                var response = Response.ok(json.body().getBytes(StandardCharsets.UTF_8)).header("Content-Type", "application/json; charset=utf-8");
                if (Formats.variesOnAccept(request)) response.header("Vary", "Accept");
                yield response.build();
            }
            case Opengraph.Unfurl.NoContent none -> Formats.head(request, 204);
        };
    }

    /// Rails' exception page for a missing parameter: there's no `public/400.html`, so it's empty.
    private Response parameterMissing() {
        request.markExceptionResponse();
        return Response.status(400).entity(new byte[0]).header("Content-Type", "text/html; charset=UTF-8").build();
    }
}
