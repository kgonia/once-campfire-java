package campfire.rooms;

import campfire.users.UserView;
import campfire.view.Safe;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/// A room's page: its messages already rendered, and whether the account's welcome note shows
/// (only in the first room, until its messages need paging).
@NullMarked
public record RoomPage(RoomView room, Instant updatedAt, UserView user, List<Safe> messages, boolean invitation, String joinCode,
                       String messagesStreamName) {
    /// The page's freshness stamp in epoch millis; the client refreshes when the room is newer.
    public long loadedAt() {
        return updatedAt.toEpochMilli();
    }
}
