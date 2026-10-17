package campfire.users;

import campfire.web.CurrentRequest;
import campfire.web.Paths;
import campfire.web.Responses;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// Banning and unbanning a user, for administrators.
@Path("/users/{user_id}/ban")
@RunOnVirtualThread
@NullMarked
public class BanResource {
    @Inject
    CurrentRequest request;
    @Inject
    UserService users;

    @POST
    public Response create(@PathParam("user_id") String userId) {
        if (!request.user().canAdminister()) return Responses.head(403);
        User user = users.find(userId);
        users.ban(user);
        return Responses.redirect(request.url(Paths.user(user.id())));
    }

    @DELETE
    public Response destroy(@PathParam("user_id") String userId) {
        if (!request.user().canAdminister()) return Responses.head(403);
        User user = users.find(userId);
        users.unban(user);
        return Responses.redirect(request.url(Paths.user(user.id())));
    }
}
