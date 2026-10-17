package campfire.bots;

import campfire.messages.Message;
import campfire.messages.MessageService;
import campfire.rooms.Room;
import campfire.rooms.RoomService;
import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
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
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
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
    public Response index(@PathParam("room_id") String roomId, @PathParam("bot_key") String botKey, @RestQuery @Nullable String before,
                          @RestQuery @Nullable String after) {
        Formats.defaultFormat(routing, "json");
        Room room = room(roomId);
        Formats.respondTo(routing, "json");
        var page = api.page(pages.view(), room, Params.presence(before), Params.presence(after), origin.baseUrl());
        var response = json(200, page.json()).header("X-Total-Count", Long.toString(page.total()));
        if (page.nextKey() != null) {
            response.header("Link", "<" + origin.url(Paths.roomBotMessages(room.id(), botKey) + "?" + page.nextKey() + "=" + page.nextId()) + ">; rel=\"next\"");
        }
        return response.build();
    }

    @POST
    public Response create(@PathParam("room_id") String roomId, byte[] body) {
        return create(roomId, null, text(body));
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response createWithAttachment(@PathParam("room_id") String roomId, MultipartFormDataInput form) {
        return create(roomId, Params.of(routing.request(), form).get("attachment"), "");
    }

    private Response create(String roomId, @Nullable Object attachment, String body) {
        Formats.defaultFormat(routing, "json");
        Room room = room(roomId);
        if (blank(attachment) && body.isBlank()) throw new Halt(Responses.head(422));
        var posted = attachment != null ? messages.post(pages.detachedView(), room, viewer.user(), null, attachment, null)
            : messages.post(pages.detachedView(), room, viewer.user(), body, null, null);
        Response created = Formats.head(routing, 201);
        created.getHeaders().putSingle("Location", origin.url(Paths.message(posted.message())));
        return created;
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("room_id") String roomId, @PathParam("id") String id, byte[] body) {
        return update(roomId, id, body);
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response replaceWithAttachment(@PathParam("room_id") String roomId, @PathParam("id") String id, MultipartFormDataInput form) {
        return updateWithAttachment(roomId, id, form);
    }

    /// Answers the edited message as JSON, or, for an HTML request, redirects to it.
    @PATCH
    @Path("{id}")
    public Response update(@PathParam("room_id") String roomId, @PathParam("id") String id, byte[] body) {
        return update(roomId, id, null, text(body));
    }

    @PATCH
    @Path("{id}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response updateWithAttachment(@PathParam("room_id") String roomId, @PathParam("id") String id, MultipartFormDataInput form) {
        return update(roomId, id, Params.of(routing.request(), form).get("attachment"), "");
    }

    private Response update(String roomId, String id, @Nullable Object attachment, String body) {
        Formats.defaultFormat(routing, "json");
        Room room = room(roomId);
        Message message = messages.find(room, id);
        requireAdministrator(message);
        Message edited = attachment != null ? messages.replaceAttachment(pages.detachedView(), room, message, attachment)
            : messages.edit(pages.detachedView(), room, message, body);
        if ("html".equals(Formats.format(routing))) return Responses.redirect(origin.url(Paths.roomMessage(room.id(), edited.id())));
        return json(200, api.messageJson(pages.view(), edited, origin.baseUrl())).build();
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("room_id") String roomId, @PathParam("id") String id) {
        Formats.defaultFormat(routing, "json");
        Room room = room(roomId);
        Message message = messages.find(room, id);
        requireAdministrator(message);
        messages.delete(room, message);
        return Response.noContent().build();
    }

    /// One of the bot's rooms, or an empty 404.
    private Room room(String roomId) {
        Long id = Params.parseId(roomId);
        return (id == null ? java.util.Optional.<Room>empty() : rooms.findForUser(viewer.user().id(), id))
            .orElseThrow(() -> new Halt(Responses.head(404)));
    }

    private void requireAdministrator(Message message) {
        if (!viewer.user().canAdminister(message.creatorId())) throw new Halt(Responses.head(403));
    }

    private static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static boolean blank(@Nullable Object value) {
        return value == null || value instanceof String s && s.isBlank();
    }

    private static Response.ResponseBuilder json(int status, String json) {
        return Response.status(status).entity(json.getBytes(StandardCharsets.UTF_8)).header("Content-Type", "application/json; charset=utf-8");
    }
}
