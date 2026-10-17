package campfire.messages;

import campfire.web.Params;
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
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import org.jboss.resteasy.reactive.RestHeader;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// `/messages` routes to the same actions as `/rooms/:room_id/messages`, with the room taken
/// from a `room_id` query or form parameter if there is one; without it the room isn't found
/// (404, or the "room not found" note when posting).
@Path("/messages")
@RunOnVirtualThread
@NullMarked
public class RoomlessMessageResource {
    @Inject
    MessageResource messages;
    @Inject
    RoutingContext routing;

    @GET
    public Response index(Request http, @RestQuery("room_id") @Nullable String roomId, @RestQuery @Nullable String before,
                          @RestQuery @Nullable String after, @RestHeader("Turbo-Frame") @Nullable String turboFrame) {
        return messages.index(Objects.requireNonNullElse(roomId, ""), http, before, after, turboFrame);
    }

    @POST
    public Response create(MultipartFormDataInput form) {
        return messages.create(roomId(form), form);
    }

    @GET
    @Path("{id}")
    public Response show(@PathParam("id") String id, @RestQuery("room_id") @Nullable String roomId) {
        return messages.show(Objects.requireNonNullElse(roomId, ""), id);
    }

    @GET
    @Path("{id}/edit")
    public Response edit(@PathParam("id") String id, @RestQuery("room_id") @Nullable String roomId) {
        return messages.edit(Objects.requireNonNullElse(roomId, ""), id);
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id, MultipartFormDataInput form) {
        return messages.update(roomId(form), id, form);
    }

    @PATCH
    @Path("{id}")
    public Response update(@PathParam("id") String id, MultipartFormDataInput form) {
        return messages.update(roomId(form), id, form);
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id, MultipartFormDataInput form) {
        return messages.destroy(roomId(form), id);
    }

    private String roomId(MultipartFormDataInput form) {
        return Objects.requireNonNullElse(Params.of(routing.request(), form).string("room_id"), "");
    }
}
