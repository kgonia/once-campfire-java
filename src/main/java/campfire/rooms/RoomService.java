package campfire.rooms;

import campfire.cable.Broadcasts;
import campfire.cable.Cable;
import campfire.db.Database;
import campfire.messages.Message;
import campfire.messages.MessagePresenter;
import campfire.users.User;
import campfire.users.UserView;
import campfire.view.View;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@ApplicationScoped
@NullMarked
public class RoomService {
    @Inject
    Database db;
    @Inject
    Broadcasts broadcasts;
    @Inject
    Cable cable;

    /// No socket outlives the process, so memberships the previous process left connected are
    /// reset before the first request, as Rails does when Puma boots. Otherwise presence and unread
    /// marks treat those members as online until `Membership.CONNECTION_TTL` passes.
    void disconnectAll(@Observes StartupEvent startup) {
        db.write(tx -> {
            Membership.disconnectAll(tx);
            return null;
        });
    }

    public Optional<Room> findForUser(long userId, long roomId) {
        return Optional.ofNullable(db.read(conn -> Room.findForUser(conn, userId, roomId)));
    }

    /// The user's membership with its room. Memberships have no foreign key to rooms; one whose
    /// room is gone is an error rather than "not found", as in Rails.
    public Optional<Membership.WithRoom> membership(long userId, long roomId) {
        return Optional.ofNullable(db.read(conn -> {
            Membership membership = Membership.find(conn, roomId, userId);
            if (membership == null) return null;
            Room room = Room.find(conn, membership.roomId());
            if (room == null) throw new IllegalStateException("the membership's room is gone");
            return new Membership.WithRoom(membership, room);
        }));
    }

    /// The room most recently created among the user's.
    public Optional<Room> newestForUser(long userId) {
        return Optional.ofNullable(db.read(conn -> Room.lastForUser(conn, userId)));
    }

    public boolean anyForUser(long userId) {
        return db.read(conn -> Room.anyForUser(conn, userId));
    }

    /// See {@link Room#lastVisitedForUser}.
    public Optional<Long> lastVisited(long userId, @Nullable Long cookieRoomId) {
        return Optional.ofNullable(db.read(conn -> Room.lastVisitedForUser(conn, userId, cookieRoomId))).map(Room::id);
    }

    /// The room's page for `user`: the newest messages, or those around `aroundMessageId` when
    /// that message is in the room.
    public RoomPage page(View v, Room room, User user, @Nullable Long aroundMessageId) {
        return db.read(conn -> {
            Message around = aroundMessageId == null ? null : Message.find(conn, aroundMessageId);
            List<Message> messages = around != null && around.roomId() == room.id() ? Message.pageAround(conn, room.id(), around)
                : Message.lastPage(conn, room.id());
            Long original = Room.originalId(conn);
            boolean invitation = original != null && original == room.id() && !Message.paged(conn, room.id());
            return new RoomPage(new RoomNames(conn).view(room, user), room.updatedAt(), UserView.of(user, v),
                new MessagePresenter(conn, v).render(messages), invitation, v.account == null ? "" : v.account.joinCode(),
                v.signedStreamName(Broadcasts.roomGid(room).toParam(), Broadcasts.MESSAGES));
        });
    }

    /// Deletes the room with its memberships and messages, takes it out of everyone's sidebar, and
    /// drops its former members' sockets, whose subscriptions to it are no longer authorized.
    public void destroy(Room room) {
        List<Long> memberIds = db.write(tx -> Room.destroy(tx, room));
        broadcasts.roomRemove(room);
        memberIds.forEach(cable::disconnectUser);
    }
}
