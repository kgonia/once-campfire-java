package campfire.rooms;

import campfire.db.Conn;
import campfire.users.User;
import campfire.view.View;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Room names as one user sees them, looked up on the connection a page reads on. A direct room
/// is named after its other members ("Ann and Bob"), or after the user alone in it.
@NullMarked
public final class RoomNames {
    private final Conn conn;
    private final Map<Long, String> anonymous = new HashMap<>();

    public RoomNames(Conn conn) {
        this.conn = conn;
    }

    /// `forUser` null names a direct room after all its members, as broadcasts show it.
    public String displayName(Room room, @Nullable User forUser) {
        if (!room.isDirect()) return room.name() == null ? "" : room.name();
        List<String> names = User.inRoom(conn, room.id()).stream().filter(u -> forUser == null || u.id() != forUser.id()).map(User::name).toList();
        String sentence = View.toSentence(names);
        return sentence.isBlank() ? (forUser == null ? "" : forUser.name()) : sentence;
    }

    public RoomView view(Room room, @Nullable User forUser) {
        return new RoomView(room.id(), RoomKind.of(room), room.name(), displayName(room, forUser));
    }

    /// The name shared fragments show: for no user in particular, "" once the room is gone.
    public String displayName(long roomId) {
        return anonymous.computeIfAbsent(roomId, id -> {
            Room room = Room.find(conn, id);
            return room == null ? "" : displayName(room, null);
        });
    }
}
