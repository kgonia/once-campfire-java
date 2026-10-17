package campfire.web;

import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;

/// `/up`, for load balancers and uptime monitors: green while the app answers at all. It reads
/// nothing, so a busy database doesn't turn it red.
@Path("/up")
@Access.Framework
@RunOnVirtualThread
public class HealthResource {
    @Inject
    RoutingContext routing;

    @GET
    public Response show() {
        Response.ResponseBuilder response;
        if (Formats.respondTo(routing, "html", "json").equals("json")) {
            var status = new LinkedHashMap<String, Object>();
            status.put("status", "up");
            status.put("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
            response = Response.fromResponse(Responses.json(200, status));
        } else {
            response = Responses.html(200, "<!DOCTYPE html><html><body style=\"background-color: green\"></body></html>");
        }
        if (Formats.variesOnAccept(routing)) response.header("Vary", "Accept");
        return response.build();
    }
}
