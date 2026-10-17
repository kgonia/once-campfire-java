package campfire.rooms;

import campfire.view.Pages;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.rooms.involvements.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The notification bell of a room the user is in.
@Path("/rooms/{room_id}/involvement")
@RunOnVirtualThread
@NullMarked
public class InvolvementResource {
    private static final List<String> INVOLVEMENTS = List.of("invisible", "nothing", "mentions", "everything");

    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    @Inject
    RoomAccess access;
    @Inject
    InvolvementService involvements;
    @Inject
    Pages pages;

    @GET
    public Response show(@PathParam("room_id") String roomId) {
        Membership.WithRoom found = access.membership(roomId);
        Room room = found.room();
        RoomKind kind = RoomKind.of(room);
        String involvement = found.membership().involvement() == null ? "" : found.membership().involvement();
        return pages.page((out, v) -> JteshowGenerated.render(out, null, v, room.id(), kind.paramKey, kind.direct(), involvement));
    }

    @PUT
    public Response replace(@PathParam("room_id") String roomId, MultipartFormDataInput form) {
        return update(roomId, form);
    }

    @PATCH
    public Response update(@PathParam("room_id") String roomId, MultipartFormDataInput form) {
        Membership.WithRoom found = access.membership(roomId);
        involvements.update(pages.detachedView(), found, involvementParam(Params.of(routing.request(), form)));
        return Responses.redirect(origin.url(Paths.roomInvolvement(found.room().id())));
    }

    /// `involvement` as the enum attribute casts it: blank (missing, "", spaces, an empty array or
    /// hash) is null; anything else not among the values fails with a 500.
    private static @Nullable String involvementParam(Params params) {
        Object param = params.get("involvement");
        if (param == null || param instanceof String s && s.isBlank() || param instanceof List<?> l && l.isEmpty()
            || param instanceof Map<?, ?> m && m.isEmpty()) return null;
        if (param instanceof String s && INVOLVEMENTS.contains(s)) return s;
        throw new IllegalArgumentException("'" + param + "' is not a valid involvement");
    }
}
