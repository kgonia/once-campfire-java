package campfire.pwa;

import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// turbo-rails' navigation endpoints: the native apps intercept these URLs (a redirect to one
/// tells them to go back, stay or reload), so the pages only matter to plain browsers.
@Path("/{action: (?:recede|resume|refresh)}_historical_location")
@Access.Framework
@RunOnVirtualThread
@NullMarked
public class TurboNativeResource {
    @Inject
    CurrentRequest request;

    @GET
    public Response show(@PathParam("action") String action) {
        String text = switch (action) {
            case "recede" -> "Going back…";
            case "resume" -> "Staying put…";
            default -> "Refreshing…";
        };
        var response = CurrentRequest.html(200, text);
        if (Formats.variesOnAccept(request)) response.header("Vary", "Accept");
        return response.build();
    }
}
