package campfire.sidebar;

import campfire.users.User;
import campfire.view.Safe;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/// The sidebar: direct rooms (already rendered, newest activity first), then up to twenty people
/// to start a direct room with, then the shared rooms by name. The two streams keep it current.
@NullMarked
public record SidebarPage(User currentUser, String roomsStream, String userRoomsStream, List<Safe> directRooms,
                          List<User> directPlaceholderUsers, List<SharedRoom> sharedRooms, boolean canCreateRooms) {
    public record SharedRoom(long id, String paramKey, String name, boolean unread) {
        public String classNames() {
            return unread ? "align-center gap room btn txt-nowrap unread" : "align-center gap room btn txt-nowrap";
        }
    }
}
