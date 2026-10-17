package campfire.rooms;

import campfire.db.Database;
import campfire.messages.Message;
import campfire.messages.MessagePresenter;
import campfire.view.Safe;
import campfire.view.View;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/// What changed in a room since a client last loaded it, for clients that missed broadcasts
/// (a sleeping laptop, a dropped socket).
@ApplicationScoped
@NullMarked
public class RoomRefreshService {
    @Inject
    Database db;

    /// Up to a page of messages created after `since`, and up to a page of the others edited
    /// after it, each oldest first.
    public record Refresh(List<Safe> created, List<RefreshedMessage> updated) {}

    public Refresh since(View v, Room room, Instant since) {
        return db.read(conn -> {
            var presenter = new MessagePresenter(conn, v);
            List<Message> created = Message.pageCreatedSince(conn, room.id(), since);
            List<Message> updated = Message.pageUpdatedSince(conn, room.id(), since, created.stream().map(Message::id).toList());
            List<Safe> updatedHtml = presenter.render(updated);
            var replacements = new ArrayList<RefreshedMessage>(updated.size());
            for (int i = 0; i < updated.size(); i++) replacements.add(new RefreshedMessage(updated.get(i).domId(), updatedHtml.get(i)));
            return new Refresh(presenter.render(created), replacements);
        });
    }
}
