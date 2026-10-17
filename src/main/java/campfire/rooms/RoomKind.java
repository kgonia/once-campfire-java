package campfire.rooms;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The room's `type` column, with the stem its dom ids and form params use.
@NullMarked
public enum RoomKind {
    OPEN("rooms_open"), CLOSED("rooms_closed"), DIRECT("rooms_direct");

    public final String paramKey;

    RoomKind(String paramKey) {
        this.paramKey = paramKey;
    }

    public static RoomKind of(String type) {
        return switch (type) {
            case Room.OPEN -> OPEN;
            case Room.CLOSED -> CLOSED;
            default -> DIRECT;
        };
    }

    public static RoomKind of(Room room) {
        return of(room.type());
    }

    public boolean direct() {
        return this == DIRECT;
    }

    public String domId(long id, @Nullable String prefix) {
        return prefix == null || prefix.isEmpty() ? paramKey + "_" + id : prefix + "_" + paramKey + "_" + id;
    }
}
