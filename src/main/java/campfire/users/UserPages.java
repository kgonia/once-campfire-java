package campfire.users;

import campfire.db.Conn;
import campfire.rails.GlobalId;
import campfire.rails.Secrets;
import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.rooms.RoomKind;
import campfire.view.View;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/// What the user and profile pages show.
@NullMarked
public final class UserPages {
    private UserPages() {}

    /// How long a session transfer link (the QR code for signing in on another device) works.
    public static final Duration TRANSFER_LINK_EXPIRY = Duration.ofHours(4);

    /// A user as mentions and the mention autocomplete see them.
    public record MentionUser(User user, String attachableSgid) {
        public static MentionUser of(Secrets secrets, User user) {
            return new MentionUser(user, secrets.attachableSgid(GlobalId.of("User", user.id())));
        }
    }

    /// `transferId` signs the user in on another device until it expires.
    public record UserShow(User user, String transferId) {}

    public record ProfileShow(User user, boolean avatarAttached, String transferId, List<ProfileMembership> sharedMemberships,
                              List<ProfileMembership> directMemberships) {}

    /// A room on the profile with its notification setting.
    public record ProfileMembership(long roomId, String roomParamKey, String roomDisplayName, String involvement, boolean direct) {}

    public record ProfileMemberships(List<ProfileMembership> direct, List<ProfileMembership> shared) {}

    public static String transferId(Secrets secrets, long userId) {
        return secrets.signedId("User", userId, "transfer", Instant.now().plus(TRANSFER_LINK_EXPIRY));
    }

    /// The user's rooms by name, direct ones apart.
    public static ProfileMemberships profileMemberships(Conn conn, User user) {
        var direct = new ArrayList<ProfileMembership>();
        var shared = new ArrayList<ProfileMembership>();
        for (Membership.WithRoom m : Membership.withOrderedRoom(conn, user.id())) {
            var room = m.room();
            var view = new ProfileMembership(room.id(), RoomKind.of(room).paramKey, displayName(conn, room, user),
                m.membership().involvement() == null ? "" : m.membership().involvement(), room.isDirect());
            (room.isDirect() ? direct : shared).add(view);
        }
        return new ProfileMemberships(direct, shared);
    }

    /// A direct room named after its other members, listed in the order SQLite returns a room's
    /// users unsorted (which isn't membership order).
    private static String displayName(Conn conn, Room room, User user) {
        if (!room.isDirect()) return room.name() == null ? "" : room.name();
        List<String> names = User.inRoom(conn, room.id()).stream().filter(member -> member.id() != user.id()).map(User::name).toList();
        String sentence = View.toSentence(names);
        return sentence.isBlank() ? user.name() : sentence;
    }
}
