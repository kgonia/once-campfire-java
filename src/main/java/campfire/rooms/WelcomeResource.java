package campfire.rooms;

import campfire.sessions.Viewer;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Cookies;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
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
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    Cookies cookies;
    @Inject
    Pages pages;
    @Inject
    RoomService rooms;

    @GET
    public Response show() {
        User user = viewer.user();
        if (!rooms.anyForUser(user.id())) return pages.page((out, v) -> JteshowGenerated.render(out, null, v, user.name()));
        long room = rooms.lastVisited(user.id(), Params.parseId(cookies.get("last_room"))).orElseThrow();
        return Responses.redirect(origin.url(Paths.room(room)));
    }
}
