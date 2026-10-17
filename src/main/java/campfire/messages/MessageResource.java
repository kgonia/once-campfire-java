package campfire.messages;

import campfire.cable.Broadcasts;
import campfire.rooms.Room;
import campfire.rooms.RoomAccess;
import campfire.view.PageDigest;
import campfire.view.Pages;
import campfire.view.Safe;
import campfire.view.View;
import campfire.web.CurrentRequest;
import campfire.web.HttpCache;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RecordNotFound;
import campfire.web.ResponseCache;
import campfire.web.Responses;
import gg.jte.generated.precompiled.messages.JteeditGenerated;
import gg.jte.generated.precompiled.messages.JteindexGenerated;
import gg.jte.generated.precompiled.messages.JteshowGenerated;
import gg.jte.generated.precompiled.messages.Jtecreateturbo_streamGenerated;
import gg.jte.generated.precompiled.messages.Jtedestroyturbo_streamGenerated;
import gg.jte.generated.precompiled.messages.Jteroom_not_foundGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/// Messages in a room: the page fetched while scrolling, posting from the composer (with an
/// optional attachment), and editing or deleting one's own messages.
@Path("/rooms/{room_id}/messages")
@RunOnVirtualThread
@NullMarked
public class MessageResource {
    @Inject
    CurrentRequest request;
    @Inject
    RoomAccess access;
    @Inject
    MessageService messages;
    @Inject
    Pages pages;
    @Inject
    ResponseCache cache;

    /// The page before or after a message (`?before=`/`?after=`), else the newest page; 204 when
    /// there's nothing more. No layout.
    @GET
    public Response index(@PathParam("room_id") String roomId, Request http) {
        Room room = access.membership(roomId).room();
        var cached = cache.lookup(request, "messages#index");
        if (cached.isPresent()) return cached.get();
        List<Message> page = messages.page(room, request.params().present("before"), request.params().present("after"));
        if (page.isEmpty()) return Responses.head(204);
        View v = pages.view();
        List<Safe> html = messages.render(v, page);
        byte[] body = Pages.bytes(v, (out, view) -> JteindexGenerated.render(out, null, html));
        // The messages render their creators, boosts and rich text too, which can change without
        // touching the messages, so only the body itself validates it.
        EntityTag etag = HttpCache.etag(request, PageDigest.validator(body), "messages/index");
        Response notModified = HttpCache.notModified(http, etag);
        if (notModified != null) return notModified;
        return HttpCache.tagged(pages.response(200, body), etag).build();
    }

    /// Answers with the message as a Turbo Stream append; everyone else gets it over the cable. A
    /// room deleted meanwhile answers a note in place of the composer instead of a 404.
    @POST
    public Response create(@PathParam("room_id") String roomId) {
        Room room;
        try {
            room = access.membership(roomId).room();
        } catch (RecordNotFound e) {
            return Responses.html(200, Pages.bytes(pages.view(), (out, view) -> Jteroom_not_foundGenerated.render(out, null, view))).build();
        }
        request.requireHash("message");
        Params params = request.params();
        var posted = messages.post(pages.detachedView(), room, request.user(), params.string("message", "body"),
            params.get("message", "attachment"), params.string("message", "client_message_id"));
        String target = Broadcasts.roomDomId(room, Broadcasts.MESSAGES);
        return Responses.turboStream(Pages.bytes(null, (out, v) -> Jtecreateturbo_streamGenerated.render(out, null, target, posted.html())));
    }

    @GET
    @Path("{id}")
    public Response show(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        Message message = messages.find(access.membership(roomId).room(), id);
        View v = pages.view();
        Safe html = messages.render(v, message);
        return pages.render(200, v, (out, view) -> JteshowGenerated.render(out, null, view, html));
    }

    @GET
    @Path("{id}/edit")
    public Response edit(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        Message message = messages.find(access.membership(roomId).room(), id);
        access.requireAdministrator(message.creatorId());
        View v = pages.view();
        var editing = messages.editing(v, message);
        return pages.render(200, v, (out, view) -> JteeditGenerated.render(out, null, view, editing.message(), editing.editableBody()));
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        return update(roomId, id);
    }

    @PATCH
    @Path("{id}")
    public Response update(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        Room room = access.membership(roomId).room();
        Message message = messages.find(room, id);
        access.requireAdministrator(message.creatorId());
        request.requireHash("message");
        Message edited = messages.edit(pages.detachedView(), room, message, request.params().string("message", "body"));
        // Rails has no JSON template for the edited message.
        if (request.wantsJson()) throw new IllegalStateException("Missing template messages/show");
        return Responses.redirect(request.url(Paths.roomMessage(room.id(), edited.id())));
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        Room room = access.membership(roomId).room();
        Message message = messages.find(room, id);
        access.requireAdministrator(message.creatorId());
        messages.delete(room, message);
        String target = Broadcasts.messageDomId(message, null);
        return Responses.turboStream(Pages.bytes(null, (out, v) -> Jtedestroyturbo_streamGenerated.render(out, null, target)));
    }
}
