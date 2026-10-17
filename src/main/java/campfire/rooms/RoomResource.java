package campfire.rooms;

import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.ResponseCache;
import campfire.web.Responses;
import gg.jte.generated.precompiled.rooms.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A room's page, the first page after sign-in.
@Path("/rooms")
@RunOnVirtualThread
@NullMarked
public class RoomResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RailsSession railsSession;
    @Inject
    RoutingContext routing;
    @Inject
    RoomAccess access;
    @Inject
    RoomService rooms;
    @Inject
    Pages pages;
    @Inject
    ResponseCache cache;

    /// The user's newest room. Rails fails on a user without rooms, and so does this.
    @GET
    public Response index() {
        Room room = rooms.newestForUser(viewer.user().id()).orElseThrow(() -> new IllegalStateException("No route matches room_url(nil)"));
        return Responses.redirect(origin.url(Paths.room(room.id())));
    }

    @GET
    @Path("{id}")
    public Response show(@PathParam("id") String id) {
        return show(id, null);
    }

    /// The page with a message in the middle (a permalink) rather than the newest page.
    @GET
    @Path("{room_id}/@{message_id}")
    public Response showAt(@PathParam("room_id") String roomId, @PathParam("message_id") String messageId) {
        return show(roomId, Params.parseId(messageId));
    }

    private Response show(String id, @Nullable Long aroundMessageId) {
        Room room = access.room(id, RoomAccess.Scope.ANY);
        access.rememberVisit(room.id());
        var cached = cache.lookup(viewer, railsSession, origin, routing, "rooms#show");
        if (cached.isPresent()) return cached.get();
        View v = pages.view();
        v.frameLayout = Formats.isTurboFrame(routing);
        RoomPage page = rooms.page(v, room, viewer.user(), aroundMessageId);
        return pages.render(200, v, (out, view) -> JteshowGenerated.render(out, null, view, page));
    }

    /// Routed in Rails, but to a controller that doesn't exist.
    @GET
    @Path("{room_id}/settings")
    public Response settings() {
        throw new IllegalStateException("uninitialized constant Rooms::SettingsController");
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        Room room = access.room(id, RoomAccess.Scope.ANY);
        access.requireAdministrator(room.creatorId());
        rooms.destroy(room);
        return Responses.redirect(origin.url(Paths.ROOT));
    }
}
