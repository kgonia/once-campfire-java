package campfire.rooms;

import campfire.rooms.RoomForms.DirectEdit;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.CurrentRequest;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RecordNotFound;
import campfire.web.Responses;
import gg.jte.generated.precompiled.rooms.directs.JteeditGenerated;
import gg.jte.generated.precompiled.rooms.directs.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// Direct rooms. Anyone may start one, whatever the account's room creation setting, and every
/// member may delete it.
@Path("/rooms/directs")
@RunOnVirtualThread
@NullMarked
public class DirectRoomResource {
    @Inject
    CurrentRequest request;
    @Inject
    RoomAccess access;
    @Inject
    DirectRoomService directs;
    @Inject
    RoomService rooms;
    @Inject
    RoomResource roomPages;
    @Inject
    Pages pages;

    /// Rails matches `/rooms/:id` first, so this is the page of a room with id "directs": none.
    @GET
    public Response index() {
        return roomPages.show("directs");
    }

    @GET
    @Path("new")
    public Response newRoom() {
        return pages.page((out, v) -> JtenewGenerated.render(out, null, v));
    }

    @POST
    public Response create() {
        Room room = directs.findOrCreate(pages.detachedView(), request.user(), RoomParams.userIds(request));
        return Responses.redirect(request.url(Paths.room(room.id())));
    }

    /// Off to the room page, which checks membership.
    @GET
    @Path("{id}")
    public Response show(@PathParam("id") String id) {
        Long roomId = Params.parseId(id);
        if (roomId == null) throw new RecordNotFound();
        return Responses.redirect(request.url(Paths.room(roomId)));
    }

    @GET
    @Path("{id}/edit")
    public Response edit(@PathParam("id") String id) {
        Room room = access.room(id, RoomAccess.Scope.DIRECT);
        View v = pages.view();
        v.frameLayout = request.isTurboFrame();
        DirectEdit edit = directs.edit(v, room, request.user());
        return pages.render(200, v, (out, view) -> JteeditGenerated.render(out, null, view, edit));
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        rooms.destroy(access.room(id, RoomAccess.Scope.DIRECT));
        return Responses.redirect(request.url(Paths.ROOT));
    }
}
