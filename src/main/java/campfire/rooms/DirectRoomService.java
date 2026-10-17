package campfire.rooms;

import campfire.cable.Broadcasts;
import campfire.db.Database;
import campfire.rooms.RoomForms.DirectEdit;
import campfire.sidebar.SidebarService;
import campfire.users.User;
import campfire.users.UserView;
import campfire.view.View;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/// Direct rooms ("pings"): one per set of people, found again when the same people ping.
@ApplicationScoped
@NullMarked
public class DirectRoomService {
    @Inject
    Database db;
    @Inject
    Broadcasts broadcasts;
    @Inject
    SidebarService sidebars;

    /// The direct room of the creator and `userIds` (ids naming no user ignored), created if
    /// needed. Either way it is put at the top of each member's direct rooms.
    public Room findOrCreate(View detached, User creator, List<Long> userIds) {
        var ids = new ArrayList<>(userIds);
        ids.add(creator.id());
        Room room = db.write(tx -> Room.findOrCreateDirect(tx, SharedRoomService.existingUserIds(tx, ids), creator.id()));
        for (Membership membership : db.read(conn -> Membership.forRoom(conn, room.id()))) {
            broadcasts.directRoomCreate(membership, sidebars.directRoom(detached, membership, room).toString());
        }
        return room;
    }

    /// The room's members other than the user, or the user alone in it.
    public DirectEdit edit(View v, Room room, User user) {
        return db.read(conn -> {
            List<User> users = User.inRoom(conn, room.id());
            if (users.size() > 1) users = users.stream().filter(u -> u.id() != user.id()).toList();
            return new DirectEdit(room.id(), new RoomNames(conn).displayName(room, user), users.stream().map(u -> UserView.of(u, v)).toList());
        });
    }
}
