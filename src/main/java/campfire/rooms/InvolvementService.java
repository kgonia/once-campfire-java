package campfire.rooms;

import campfire.cable.Broadcasts;
import campfire.db.Database;
import campfire.sidebar.SidebarService;
import campfire.view.View;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// How much of a room reaches its member: `invisible` (hidden from the sidebar), `nothing`,
/// `mentions` or `everything`.
@ApplicationScoped
@NullMarked
public class InvolvementService {
    @Inject
    Database db;
    @Inject
    Broadcasts broadcasts;

    /// Stores the involvement (null clears it) and, for a shared room, hides it from or shows it
    /// again in the member's sidebar when it becomes or stops being invisible.
    public void update(View detached, Membership.WithRoom found, @Nullable String involvement) {
        Room room = found.room();
        Membership before = found.membership();
        Membership after = db.write(tx -> {
            Membership.updateInvolvement(tx, before.id(), involvement);
            return Membership.find(tx, room.id(), before.userId());
        });
        broadcasts.involvementChange(room, after, before.involvement(), () -> SidebarService.sharedRoomHtml(detached, room));
    }
}
