package campfire.messages;

import campfire.web.CurrentRequest;
import io.smallrye.common.annotation.RunOnVirtualThread;
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
import org.jspecify.annotations.NullMarked;

/// `/messages` routes to the same actions as `/rooms/:room_id/messages`, with the room taken
/// from a `room_id` query or form parameter if there is one; without it the room isn't found
/// (404, or the "room not found" note when posting).
@Path("/messages")
@RunOnVirtualThread
@NullMarked
public class RoomlessMessageResource {
    @Inject
    CurrentRequest request;
    @Inject
    MessageResource messages;

    @GET
    public Response index(Request http) {
        return messages.index(roomId(), http);
    }

    @POST
    public Response create() {
        return messages.create(roomId());
    }

    @GET
    @Path("{id}")
    public Response show(@PathParam("id") String id) {
        return messages.show(roomId(), id);
    }

    @GET
    @Path("{id}/edit")
    public Response edit(@PathParam("id") String id) {
        return messages.edit(roomId(), id);
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id) {
        return messages.update(roomId(), id);
    }

    @PATCH
    @Path("{id}")
    public Response update(@PathParam("id") String id) {
        return messages.update(roomId(), id);
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        return messages.destroy(roomId(), id);
    }

    private String roomId() {
        return Objects.requireNonNullElse(request.params().string("room_id"), "");
    }
}
