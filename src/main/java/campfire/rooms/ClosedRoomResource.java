package campfire.rooms;

import campfire.rooms.RoomForms.ClosedForm;
import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.rooms.closeds.JteeditGenerated;
import gg.jte.generated.precompiled.rooms.closeds.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;

/// Closed rooms: only the people chosen in the form are members.
@Path("/rooms/closeds")
@RunOnVirtualThread
@NullMarked
public class ClosedRoomResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    @Inject
    RoomAccess access;
    @Inject
    SharedRoomService rooms;
    @Inject
    RoomResource roomPages;
    @Inject
    Pages pages;

    /// Rails matches `/rooms/:id` first, so this is the page of a room with id "closeds": none.
    @GET
    public Response index() {
        return roomPages.show("closeds");
    }

    @GET
    @Path("new")
    public Response newRoom() {
        access.requireRoomCreation();
        View v = pages.view();
        v.frameLayout = Formats.isTurboFrame(routing);
        ClosedForm form = rooms.newClosedForm(v, viewer.user());
        return pages.render(200, v, (out, view) -> JtenewGenerated.render(out, null, view, form));
    }

    @POST
    public Response create(MultipartFormDataInput form) {
        Params params = Params.of(routing.request(), form);
        access.requireRoomCreation();
        String name = RoomParams.name(params).name();
        List<Long> granteeIds = RoomParams.userIds(params);
        Room room = rooms.createClosed(pages.detachedView(), viewer.user(), name, granteeIds);
        return Responses.redirect(origin.url(Paths.room(room.id())));
    }

    @GET
    @Path("{id}")
    public Response show(@PathParam("id") String id) {
        Room room = access.room(id, RoomAccess.Scope.SHARED);
        access.rememberVisit(room.id());
        return Responses.redirect(origin.url(Paths.room(room.id())));
    }

    @GET
    @Path("{id}/edit")
    public Response edit(@PathParam("id") String id) {
        Room room = access.room(id, RoomAccess.Scope.SHARED);
        View v = pages.view();
        v.frameLayout = Formats.isTurboFrame(routing);
        ClosedForm form = rooms.closedForm(v, room, viewer.user());
        return pages.render(200, v, (out, view) -> JteeditGenerated.render(out, null, view, form));
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id, MultipartFormDataInput form) {
        return update(id, form);
    }

    @PATCH
    @Path("{id}")
    public Response update(@PathParam("id") String id, MultipartFormDataInput form) {
        Params params = Params.of(routing.request(), form);
        Room room = access.room(id, RoomAccess.Scope.SHARED);
        access.requireAdministrator(room.creatorId());
        RoomParams.Name name = RoomParams.name(params);
        List<Long> granteeIds = RoomParams.userIds(params);
        Room updated = rooms.updateClosed(pages.detachedView(), room, name.given() ? name.name() : room.name(), granteeIds);
        return Responses.redirect(origin.url(Paths.room(updated.id())));
    }

    /// Rails looks the room up for every action but this one, and then fails on the missing room.
    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        throw new IllegalStateException("undefined method 'destroy' for nil");
    }
}
