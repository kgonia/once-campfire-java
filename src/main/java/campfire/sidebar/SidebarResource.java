package campfire.sidebar;

import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.Formats;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.ResponseCache;
import gg.jte.generated.precompiled.users.sidebars.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// The room list, loaded into the sidebar's Turbo Frame on every room page. Only `/users/me/...` is
/// linked, but any user id segment reaches the signed-in user's own sidebar, as in Rails.
@Path("/users/{user_id}/sidebar")
@RunOnVirtualThread
@NullMarked
public class SidebarResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RailsSession railsSession;
    @Inject
    RoutingContext routing;
    @Inject
    Pages pages;
    @Inject
    ResponseCache cache;
    @Inject
    SidebarService sidebars;

    @GET
    public Response show() {
        var cached = cache.lookup(viewer, railsSession, origin, routing, "users/sidebars#show");
        if (cached.isPresent()) return cached.get();
        View v = pages.view();
        v.frameLayout = Formats.isTurboFrame(routing);
        SidebarPage sidebar = sidebars.page(v, viewer.user());
        return pages.render(200, v, (out, view) -> JteshowGenerated.render(out, null, view, sidebar));
    }
}
