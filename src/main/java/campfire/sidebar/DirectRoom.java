package campfire.sidebar;

import campfire.users.User;
import campfire.view.View;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/// A direct room's sidebar entry. `members` are the room's other users, or the user alone in it;
/// never empty.
@NullMarked
public record DirectRoom(long roomId, boolean unread, long updatedAtEpoch, List<User> members) {
    public String classNames() {
        return unread ? "direct unread" : "direct";
    }

    /// Up to three initials per member, joined as a sentence with "+" between two: "AB+CD".
    public String memberInitials() {
        var initials = new ArrayList<String>();
        for (User member : members) {
            var out = new StringBuilder();
            member.nameParts().stream().limit(3).forEach(part -> out.append(View.capitalize(part.substring(0, part.offsetByCodePoints(0, 1)))));
            initials.add(out.toString());
        }
        return View.toSentence(initials, "+");
    }
}
