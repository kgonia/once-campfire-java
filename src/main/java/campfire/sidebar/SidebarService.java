package campfire.sidebar;

import campfire.cable.Broadcasts;
import campfire.db.Conn;
import campfire.db.Database;
import campfire.db.Times;
import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.rooms.RoomKind;
import campfire.users.User;
import campfire.view.FragmentCache;
import campfire.view.Pages;
import campfire.view.Safe;
import campfire.view.View;
import gg.jte.generated.precompiled.users.sidebars.rooms.Jte_directGenerated;
import gg.jte.generated.precompiled.users.sidebars.rooms.Jte_sharedGenerated;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.NullMarked;

@ApplicationScoped
@NullMarked
public class SidebarService {
    /// Direct-room suggestions shown, counting the user themselves.
    static final int DIRECT_PLACEHOLDERS = 20;

    @Inject
    Database db;

    public SidebarPage page(View v, User user) {
        boolean canCreateRooms = user.isAdministrator() || v.account == null || !v.account.restrictRoomCreationToAdministrators();
        return db.read(conn -> {
            List<Membership.WithRoom> memberships = Membership.visibleWithOrderedRoom(conn, user.id());
            // Sorted oldest first and then reversed, so rooms updated at the same time come out in
            // the reverse of the query's order.
            var direct = new ArrayList<>(memberships.stream().filter(m -> m.room().isDirect())
                .sorted(Comparator.comparing((Membership.WithRoom m) -> m.room().updatedAt())).toList());
            Collections.reverse(direct);
            var directRooms = direct.stream().map(m -> directRoom(conn, v, m.membership(), m.room())).toList();
            var sharedRooms = memberships.stream().filter(m -> !m.room().isDirect())
                .map(m -> sharedRoom(m.room(), m.membership().isUnread())).toList();
            return new SidebarPage(user, v.signedStreamName("rooms"), v.signedStreamName(Broadcasts.userGid(user.id()).toParam(), "rooms"),
                directRooms, directPlaceholderUsers(conn, user), sharedRooms, canCreateRooms);
        });
    }

    /// The entry as the member sees it, rendered once per membership version.
    public Safe directRoom(View v, Membership membership, Room room) {
        return db.read(conn -> directRoom(conn, v, membership, room));
    }

    /// The shared room's entry as broadcasts carry it: read, for every member alike.
    public static String sharedRoomHtml(View v, Room room) {
        SidebarPage.SharedRoom entry = sharedRoom(room, false);
        return new String(Pages.bytes(v, (out, view) -> Jte_sharedGenerated.render(out, null, entry)), StandardCharsets.UTF_8);
    }

    public static SidebarPage.SharedRoom sharedRoom(Room room, boolean unread) {
        return new SidebarPage.SharedRoom(room.id(), RoomKind.of(room).paramKey, room.name() == null ? "" : room.name(), unread);
    }

    private static Safe directRoom(Conn conn, View v, Membership membership, Room room) {
        return FragmentCache.fetch("d/" + membership.id() + "-" + Times.toDb(membership.updatedAt()), () -> {
            DirectRoom direct = directRoom(conn, membership, room);
            return Pages.bytes(v, (out, view) -> Jte_directGenerated.render(out, null, view, direct));
        });
    }

    private static DirectRoom directRoom(Conn conn, Membership membership, Room room) {
        List<User> users = User.inRoom(conn, room.id());
        var others = new ArrayList<User>();
        for (User u : users) if (u.id() != membership.userId()) others.add(u);
        if (others.isEmpty()) {
            User own = User.find(conn, membership.userId());
            if (own != null) others.add(own);
        }
        return new DirectRoom(room.id(), membership.isUnread(), room.updatedAt().toEpochMilli(), others);
    }

    /// Active users the user has no direct room with yet. The user's own id is excluded twice when
    /// they're in a direct room alone, and the limit counts both.
    private static List<User> directPlaceholderUsers(Conn conn, User user) {
        var exclude = new ArrayList<>(Membership.userIdsInDirectRoomsWith(conn, user.id()));
        exclude.add(user.id());
        return User.activeExcluding(conn, exclude, Math.max(0, DIRECT_PLACEHOLDERS - exclude.size()));
    }
}
