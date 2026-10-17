package campfire.rooms;

import campfire.cable.Broadcasts;
import campfire.cable.Cable;
import campfire.db.Conn;
import campfire.db.Database;
import campfire.rooms.RoomForms.ClosedForm;
import campfire.rooms.RoomForms.FormRoom;
import campfire.rooms.RoomForms.OpenForm;
import campfire.sidebar.SidebarService;
import campfire.users.User;
import campfire.users.UserView;
import campfire.view.View;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Creating and changing open rooms (everyone is in them) and closed rooms (only those granted
/// access). Each change reaches the sidebars of the people who can see the room; broadcasts render
/// with the detached view passed in.
@ApplicationScoped
@NullMarked
public class SharedRoomService {
    @Inject
    Database db;
    @Inject
    Broadcasts broadcasts;
    @Inject
    Cable cable;

    public OpenForm newOpenForm(View v) {
        return db.read(conn -> new OpenForm(new FormRoom(null, RoomForms.DEFAULT_ROOM_NAME), true, activeUsers(conn, v)));
    }

    public OpenForm openForm(View v, Room room, User user) {
        return db.read(conn -> new OpenForm(new FormRoom(room.id(), room.name()), user.canAdminister(room.creatorId()), activeUsers(conn, v)));
    }

    /// Every active user offered for access, none selected yet.
    public ClosedForm newClosedForm(View v, User user) {
        return db.read(conn -> new ClosedForm(new FormRoom(null, RoomForms.DEFAULT_ROOM_NAME), true, user.id(), List.of(), activeUsers(conn, v)));
    }

    public ClosedForm closedForm(View v, Room room, User user) {
        return db.read(conn -> {
            List<Long> memberIds = Room.userIds(conn, room.id());
            var selected = new ArrayList<UserView>();
            var unselected = new ArrayList<UserView>();
            for (User u : User.activeOrdered(conn)) (memberIds.contains(u.id()) ? selected : unselected).add(UserView.of(u, v));
            return new ClosedForm(new FormRoom(room.id(), room.name()), user.canAdminister(room.creatorId()), user.id(), selected, unselected);
        });
    }

    public Room createOpen(View detached, User creator, @Nullable String name) {
        Room room = db.write(tx -> Room.createFor(tx, Room.OPEN, name, creator.id(), List.of(creator.id())));
        broadcasts.openRoomCreate(SidebarService.sharedRoomHtml(detached, room));
        return room;
    }

    /// Renames the room and makes it open (a closed room becomes open, granting everyone access).
    public Room updateOpen(View detached, Room room, @Nullable String name) {
        Room updated = db.write(tx -> Room.update(tx, room, name, Room.OPEN));
        broadcasts.openRoomUpdate(updated, SidebarService.sharedRoomHtml(detached, updated));
        return updated;
    }

    /// `granteeIds` that name no user are ignored.
    public Room createClosed(View detached, User creator, @Nullable String name, List<Long> granteeIds) {
        Room room = db.write(tx -> Room.createFor(tx, Room.CLOSED, name, creator.id(), existingUserIds(tx, granteeIds)));
        String html = SidebarService.sharedRoomHtml(detached, room);
        db.read(conn -> {
            broadcasts.closedRoomCreate(conn, room, html);
            return null;
        });
        return room;
    }

    /// Renames the room, makes it closed, and leaves exactly `granteeIds` with access. Those who
    /// lose it have their sockets dropped, since their subscriptions to it are no longer authorized.
    public Room updateClosed(View detached, Room room, @Nullable String name, List<Long> granteeIds) {
        record Updated(Room room, List<Long> revoked) {}
        Updated updated = db.write(tx -> {
            Room saved = Room.update(tx, room, name, Room.CLOSED);
            List<Long> granted = existingUserIds(tx, granteeIds);
            List<Long> revokees = Room.userIds(tx, saved.id()).stream().filter(id -> !granteeIds.contains(id)).toList();
            if (!granted.isEmpty()) Membership.grantTo(tx, saved, granted);
            List<Long> revoked = revokees.isEmpty() ? List.of() : Membership.revokeFrom(tx, saved.id(), revokees);
            return new Updated(saved, revoked);
        });
        updated.revoked().forEach(cable::disconnectUser);
        String html = SidebarService.sharedRoomHtml(detached, updated.room());
        db.read(conn -> {
            broadcasts.closedRoomUpdate(conn, updated.room(), html);
            return null;
        });
        return updated.room();
    }

    /// Active users by name, as the forms list them.
    private static List<UserView> activeUsers(Conn conn, View v) {
        return User.activeOrdered(conn).stream().map(u -> UserView.of(u, v)).toList();
    }

    /// The ids among `ids` that name a user, ascending.
    static List<Long> existingUserIds(Conn conn, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return User.findAll(conn, ids).stream().map(User::id).sorted().toList();
    }
}
