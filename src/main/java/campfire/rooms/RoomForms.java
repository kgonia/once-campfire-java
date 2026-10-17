package campfire.rooms;

import campfire.users.UserView;
import campfire.web.Paths;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The room settings pages: the open and closed room forms and the direct room's member list.
@NullMarked
public final class RoomForms {
    private RoomForms() {}

    /// The name a new open or closed room's form starts with.
    public static final String DEFAULT_ROOM_NAME = "New room";

    /// The room being created or edited by the open and closed room forms; `id` is null for a new
    /// record.
    public record FormRoom(@Nullable Long id, @Nullable String name) {
        public boolean persisted() {
            return id != null;
        }

        public String displayName() {
            return name == null ? "" : name;
        }

        public String action(boolean open) {
            if (id == null) return open ? Paths.ROOMS_OPENS : Paths.ROOMS_CLOSEDS;
            return (open ? "/rooms/opens/" : "/rooms/closeds/") + id;
        }

        public long idOrZero() {
            return id == null ? 0 : id;
        }
    }

    /// `canAdminister`: the user is an administrator or the room's creator, or the room is new.
    /// `users` are all active users by name.
    public record OpenForm(FormRoom room, boolean canAdminister, List<UserView> users) {}

    /// Active users with access (none for a new room), then the rest.
    public record ClosedForm(FormRoom room, boolean canAdminister, long currentUserId, List<UserView> selectedUsers,
                             List<UserView> unselectedUsers) {
        public int userCount() {
            return selectedUsers.size() + unselectedUsers.size();
        }
    }

    /// `users` are the room's members other than the user, or the user alone in it.
    public record DirectEdit(long roomId, String displayName, List<UserView> users) {}
}
