package campfire.rooms;

import campfire.cable.Broadcasts;
import campfire.rails.RubyStrings;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Responses;
import gg.jte.generated.precompiled.rooms.refreshes.Jteshowturbo_streamGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/// A Turbo Stream that appends the room's new messages and replaces its edited ones.
@Path("/rooms/{room_id}/refresh")
@RunOnVirtualThread
@NullMarked
public class RoomRefreshResource {
    /// The range a timestamp can take; a crafted `since` is clamped into it (as the Rust port's
    /// time library does) rather than failing.
    private static final Instant MIN = Instant.parse("-9999-01-01T00:00:00Z");
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999999Z");

    @Inject
    CurrentRequest request;
    @Inject
    RoomAccess access;
    @Inject
    RoomRefreshService refreshes;
    @Inject
    Pages pages;

    @GET
    public Response show(@PathParam("room_id") String roomId) {
        Room room = access.membership(roomId).room();
        Formats.respondTo(request, "turbo_stream");
        var refresh = refreshes.since(pages.view(), room, since());
        String target = Broadcasts.roomDomId(room, Broadcasts.MESSAGES);
        return Responses.turboStream(Pages.bytes(null,
            (out, v) -> Jteshowturbo_streamGenerated.render(out, null, target, refresh.created(), refresh.updated())));
    }

    /// `since` in epoch milliseconds, read the way Ruby's `to_i` reads it; missing means the epoch,
    /// and an array or hash fails with a 500.
    private Instant since() {
        long millis = switch (request.params().get("since")) {
            case String s -> RubyStrings.toI(s);
            case List<?> l -> throw new IllegalStateException("undefined method 'to_i'");
            case Map<?, ?> m -> throw new IllegalStateException("undefined method 'to_i'");
            case null, default -> 0;
        };
        Instant at = Instant.ofEpochMilli(millis);
        return at.isBefore(MIN) ? MIN : at.isAfter(MAX) ? MAX : at;
    }
}
