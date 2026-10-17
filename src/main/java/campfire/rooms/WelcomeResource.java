package campfire.rooms;

import campfire.users.User;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.Responses;
import gg.jte.generated.precompiled.welcome.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// The root: the last room the user visited, or an empty page while they belong to no rooms.
@Path("/")
@RunOnVirtualThread
@NullMarked
public class WelcomeResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    RoomService rooms;

    @GET
    public Response show() {
        User user = request.user();
        if (!rooms.anyForUser(user.id())) return pages.page((out, v) -> JteshowGenerated.render(out, null, v, user.name()));
        long room = rooms.lastVisited(user.id(), Params.parseId(request.cookie("last_room"))).orElseThrow();
        return Responses.redirect(request.url(Paths.room(room)));
    }
}
