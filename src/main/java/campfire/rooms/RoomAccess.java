package campfire.rooms;

import campfire.App;
import campfire.accounts.Account;
import campfire.web.CurrentRequest;
import campfire.web.Halt;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RecordNotFound;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// The room checks resources share. Rooms the user can't see answer differently depending on the
/// page: message pages 404, room pages redirect home with an alert.
@RequestScoped
@NullMarked
public class RoomAccess {
    /// Which of the user's rooms a page works with.
    public enum Scope { ANY, SHARED, DIRECT }

    @Inject
    CurrentRequest request;
    @Inject
    RoomService rooms;
    @Inject
    App app;

    /// The user's membership of the room and the room itself, or a 404.
    public Membership.WithRoom membership(String roomId) {
        Long id = Params.parseId(roomId);
        if (id == null) throw new RecordNotFound();
        return rooms.membership(request.user().id(), id).orElseThrow(RecordNotFound::new);
    }

    /// One of the user's rooms in `scope`, or a redirect home saying it isn't there.
    public Room room(String roomId, Scope scope) {
        Long id = Params.parseId(roomId);
        Optional<Room> room = id == null ? Optional.empty() : rooms.findForUser(request.user().id(), id);
        return room.filter(r -> switch (scope) {
                case ANY -> true;
                case SHARED -> !r.isDirect();
                case DIRECT -> r.isDirect();
            })
            .orElseThrow(() -> new Halt(request.redirectWithAlert(Paths.ROOT, "Room not found or inaccessible")));
    }

    /// 403 unless the user is an administrator or created the record.
    public void requireAdministrator(long creatorId) {
        if (!request.user().canAdminister(creatorId)) throw new Halt(request.head(403));
    }

    public void requireAdministrator() {
        if (!request.user().canAdminister()) throw new Halt(request.head(403));
    }

    /// 403 when the account lets only administrators create rooms and the user isn't one.
    public void requireRoomCreation() {
        Account account = app.account().account();
        if (account != null && account.restrictRoomCreationToAdministrators() && !request.user().isAdministrator()) throw new Halt(request.head(403));
    }

    /// Remembered in the `last_room` cookie, where the root page and the "back" links go. Only
    /// sent when it changes.
    public void rememberVisit(long roomId) {
        String id = Long.toString(roomId);
        if (!id.equals(request.cookie("last_room"))) request.setPermanentCookie("last_room", id);
    }
}
