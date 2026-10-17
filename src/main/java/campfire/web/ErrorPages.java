package campfire.web;

import campfire.assets.Assets;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/// Turns exceptions into the static `public/404.html` and `public/500.html` pages. An unknown path,
/// or a known path with the wrong method, is a 404 (Rails has no 405). A {@link Halt} carries its
/// own response.
public class ErrorPages {
    @Inject
    Assets assets;
    @Inject
    CurrentRequest request;

    @ServerExceptionMapper
    public Response notFound(NotFoundException e) {
        return page(404);
    }

    @ServerExceptionMapper
    public Response notAllowed(NotAllowedException e) {
        return page(404);
    }

    @ServerExceptionMapper
    public Response missingRecord(RecordNotFound e) {
        return page(404);
    }

    @ServerExceptionMapper
    public Response status(HttpError e) {
        return page(e.status());
    }

    @ServerExceptionMapper
    public Response halted(WebApplicationException e) {
        return e.getResponse();
    }

    @ServerExceptionMapper
    public Response failed(RuntimeException e) {
        Log.error("request failed", e);
        return page(500);
    }

    /// JSON requests get the status as JSON instead of the page.
    private Response page(int status) {
        request.discardChanges();
        if (request.wantsJson()) {
            String error = switch (status) {
                case 400 -> "Bad Request";
                case 404 -> "Not Found";
                case 422 -> "Unprocessable Content";
                default -> "Internal Server Error";
            };
            byte[] body = ("{\"status\":" + status + ",\"error\":\"" + error + "\"}").getBytes(StandardCharsets.UTF_8);
            return Response.status(status).entity(body).header("Content-Type", "application/json; charset=UTF-8")
                .header("Content-Length", body.length).build();
        }
        // Statuses without a static page (400, 406) answer an empty one.
        var file = assets.file("/" + status + ".html");
        byte[] body = file != null ? file.body() : new byte[0];
        return Response.status(status).entity(body).header("Content-Type", "text/html; charset=UTF-8").header("Content-Length", body.length).build();
    }
}
