package campfire.rooms;

import campfire.web.Paths;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A room as its pages show it to one user. `name` is null for direct rooms; `displayName` is what
/// to show (a direct room is named after its other members).
@NullMarked
public record RoomView(long id, RoomKind kind, @Nullable String name, String displayName) {
    public String domId(@Nullable String prefix) {
        return kind.domId(id, prefix);
    }

    public boolean direct() {
        return kind.direct();
    }

    public String editPath() {
        return switch (kind) {
            case OPEN -> Paths.editRoomsOpen(id);
            case CLOSED -> Paths.editRoomsClosed(id);
            case DIRECT -> Paths.roomsDirect(id) + "/edit";
        };
    }

    /// How the room's own pages refer to it: "Ping" for direct rooms, "room" otherwise.
    public String noun() {
        return direct() ? "Ping" : "room";
    }
}
