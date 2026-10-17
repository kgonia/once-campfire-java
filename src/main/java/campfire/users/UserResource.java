package campfire.users;

import campfire.rails.Secrets;
import campfire.sessions.SessionService;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import gg.jte.generated.precompiled.users.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// A user's page. Administrators also see the user's email, transfer link and ban button.
@Path("/users/{id}")
@RunOnVirtualThread
@NullMarked
public class UserResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    UserService users;
    @Inject
    Secrets secrets;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response show(@PathParam("id") String id) {
        User user = users.find(id);
        String transferId = SessionService.transferId(secrets, user.id());
        return pages.page((out, v) -> JteshowGenerated.render(out, null, v, user, transferId));
    }
}
