package campfire.rooms;

import campfire.rooms.RoomForms.OpenForm;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.CurrentRequest;
import campfire.web.Paths;
import gg.jte.generated.precompiled.rooms.opens.JteeditGenerated;
import gg.jte.generated.precompiled.rooms.opens.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// Open rooms: everyone is a member.
@Path("/rooms/opens")
@RunOnVirtualThread
@NullMarked
public class OpenRoomResource {
    @Inject
    CurrentRequest request;
    @Inject
    RoomAccess access;
    @Inject
    SharedRoomService rooms;
    @Inject
    RoomResource roomPages;
    @Inject
    Pages pages;

    /// Rails matches `/rooms/:id` first, so this is the page of a room with id "opens": none.
    @GET
    public Response index() {
        return roomPages.show("opens");
    }

    @GET
    @Path("new")
    public Response newRoom() {
        access.requireRoomCreation();
        View v = pages.view();
        v.frameLayout = request.isTurboFrame();
        OpenForm form = rooms.newOpenForm(v);
        return pages.render(200, v, (out, view) -> JtenewGenerated.render(out, null, view, form));
    }

    @POST
    public Response create() {
        access.requireRoomCreation();
        String name = RoomParams.name(request).name();
        Room room = rooms.createOpen(pages.detachedView(), request.user(), name);
        return request.redirect(Paths.room(room.id()));
    }

    @GET
    @Path("{id}")
    public Response show(@PathParam("id") String id) {
        Room room = access.room(id, RoomAccess.Scope.SHARED);
        access.rememberVisit(room.id());
        return request.redirect(Paths.room(room.id()));
    }

    @GET
    @Path("{id}/edit")
    public Response edit(@PathParam("id") String id) {
        Room room = access.room(id, RoomAccess.Scope.SHARED);
        View v = pages.view();
        v.frameLayout = request.isTurboFrame();
        OpenForm form = rooms.openForm(v, room, request.user());
        return pages.render(200, v, (out, view) -> JteeditGenerated.render(out, null, view, form));
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id) {
        return update(id);
    }

    @PATCH
    @Path("{id}")
    public Response update(@PathParam("id") String id) {
        Room room = access.room(id, RoomAccess.Scope.SHARED);
        access.requireAdministrator(room.creatorId());
        RoomParams.Name name = RoomParams.name(request);
        Room updated = rooms.updateOpen(pages.detachedView(), room, name.given() ? name.name() : room.name());
        return request.redirect(Paths.room(updated.id()));
    }

    /// Rails looks the room up for every action but this one, and then fails on the missing room.
    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        throw new IllegalStateException("undefined method 'destroy' for nil");
    }
}
