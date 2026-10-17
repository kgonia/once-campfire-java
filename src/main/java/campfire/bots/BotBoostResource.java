package campfire.bots;

import campfire.messages.Boost;
import campfire.messages.BoostService;
import campfire.messages.Message;
import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NullMarked;

/// Bots boost messages in their rooms; the raw request body is the boost's content.
@Path("/rooms/{room_id}/{bot_key}/messages/{message_id}/boosts")
@RunOnVirtualThread
@Access.BotsAllowed
@NullMarked
public class BotBoostResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    @Inject
    Pages pages;
    @Inject
    BoostService boosts;
    @Inject
    BotApiService api;

    /// Answers the new boost as JSON (201); a blank body is a 422.
    @POST
    public Response create(@PathParam("room_id") String roomId, @PathParam("message_id") String messageId, byte[] body) {
        Formats.defaultFormat(routing, "json");
        Message message = message(roomId, messageId);
        String content = new String(body, StandardCharsets.UTF_8);
        if (content.isBlank()) throw new Halt(Responses.head(422));
        Boost boost = boosts.create(pages.detachedView(), message, viewer.user(), content);
        return Response.status(201).entity(api.boostJson(pages.view(), boost, message, origin.baseUrl()).getBytes(StandardCharsets.UTF_8))
            .header("Content-Type", "application/json; charset=utf-8").build();
    }

    /// Only the bot's own boosts; any other is an empty 404.
    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("room_id") String roomId, @PathParam("message_id") String messageId, @PathParam("id") String id) {
        Formats.defaultFormat(routing, "json");
        Message message = message(roomId, messageId);
        Boost boost = boosts.findOwn(message, viewer.user(), id);
        if (boost == null) return Responses.head(404);
        boosts.delete(message, boost);
        return Response.noContent().build();
    }

    /// The message in one of the bot's rooms, or an empty 404.
    private Message message(String roomId, String messageId) {
        return api.messageInRoom(viewer.user(), roomId, messageId).orElseThrow(() -> new Halt(Responses.head(404)));
    }
}
