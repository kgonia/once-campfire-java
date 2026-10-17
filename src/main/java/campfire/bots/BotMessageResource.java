package campfire.bots;

import campfire.messages.Message;
import campfire.messages.MessageService;
import campfire.rooms.Room;
import campfire.rooms.RoomService;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.Params;
import campfire.web.Paths;
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
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The bot API: a bot reads and posts messages in its rooms, authenticated by the `bot_key` path
/// segment (`<id>-<token>`). It answers JSON. A posted message's body is the raw request body, or
/// a multipart `attachment` instead.
@Path("/rooms/{room_id}/{bot_key}/messages")
@RunOnVirtualThread
@Access.BotsAllowed
@NullMarked
public class BotMessageResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    RoomService rooms;
    @Inject
    MessageService messages;
    @Inject
    BotApiService api;

    /// A page of messages, oldest first, with `X-Total-Count` and a `Link` to the next page.
    @GET
    public Response index(@PathParam("room_id") String roomId, @PathParam("bot_key") String botKey) {
        request.defaultFormat("json");
        Room room = room(roomId);
        Formats.respondTo(request, "json");
        Params params = request.params();
        var page = api.page(pages.view(), room, params.present("before"), params.present("after"), request.baseUrl());
        var response = json(200, page.json()).header("X-Total-Count", Long.toString(page.total()));
        if (page.nextKey() != null) {
            response.header("Link", "<" + request.url(Paths.roomBotMessages(room.id(), botKey) + "?" + page.nextKey() + "=" + page.nextId()) + ">; rel=\"next\"");
        }
        return response.build();
    }

    @POST
    public Response create(@PathParam("room_id") String roomId) {
        request.defaultFormat("json");
        Room room = room(roomId);
        Object attachment = request.params().get("attachment");
        String body = rawBody();
        if (blank(attachment) && body.isBlank()) throw new Halt(request.head(422));
        var posted = attachment != null ? messages.post(pages.detachedView(), room, request.user(), null, attachment, null)
            : messages.post(pages.detachedView(), room, request.user(), body, null, null);
        Response created = Formats.head(request, 201);
        created.getHeaders().putSingle("Location", request.url(Paths.message(posted.message())));
        return created;
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        return update(roomId, id);
    }

    /// Answers the edited message as JSON, or, for an HTML request, redirects to it.
    @PATCH
    @Path("{id}")
    public Response update(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        request.defaultFormat("json");
        Room room = room(roomId);
        Message message = messages.find(room, id);
        requireAdministrator(message);
        Object attachment = request.params().get("attachment");
        Message edited = attachment != null ? messages.replaceAttachment(pages.detachedView(), room, message, attachment)
            : messages.edit(pages.detachedView(), room, message, rawBody());
        if ("html".equals(request.format())) return request.redirect(Paths.roomMessage(room.id(), edited.id()));
        return json(200, api.messageJson(pages.view(), edited, request.baseUrl())).build();
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        request.defaultFormat("json");
        Room room = room(roomId);
        Message message = messages.find(room, id);
        requireAdministrator(message);
        messages.delete(room, message);
        return Response.noContent().build();
    }

    /// One of the bot's rooms, or an empty 404.
    private Room room(String roomId) {
        Long id = Params.parseId(roomId);
        return (id == null ? java.util.Optional.<Room>empty() : rooms.findForUser(request.user().id(), id))
            .orElseThrow(() -> new Halt(request.head(404)));
    }

    private void requireAdministrator(Message message) {
        if (!request.user().canAdminister(message.creatorId())) throw new Halt(request.head(403));
    }

    private String rawBody() {
        return new String(request.body(), StandardCharsets.UTF_8);
    }

    private static boolean blank(@Nullable Object value) {
        return value == null || value instanceof String s && s.isBlank();
    }

    private static Response.ResponseBuilder json(int status, String json) {
        return Response.status(status).entity(json.getBytes(StandardCharsets.UTF_8)).header("Content-Type", "application/json; charset=utf-8");
    }
}
