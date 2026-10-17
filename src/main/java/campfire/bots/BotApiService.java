package campfire.bots;

import campfire.db.Database;
import campfire.messages.Boost;
import campfire.messages.Message;
import campfire.messages.MessagePresenter;
import campfire.messages.MessageService;
import campfire.rooms.Room;
import campfire.users.User;
import campfire.users.UserCache;
import campfire.view.View;
import campfire.web.Params;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// What the bot API reads: a page of a room's messages as JSON, with the room's message count
/// and a link to the next page, and single messages and boosts as JSON.
@ApplicationScoped
@NullMarked
public class BotApiService {
    @Inject
    Database db;
    @Inject
    MessageService messages;

    /// `nextKey` is "before" or "after" (null for the last page), with `nextId` the message to
    /// page from.
    public record Page(String json, long total, @Nullable String nextKey, long nextId) {}

    /// The newest page, or the page before or after a message (`after` pages forward).
    public Page page(View v, Room room, @Nullable String before, @Nullable String after, String baseUrl) {
        List<Message> page = messages.page(room, before, after);
        return db.read(conn -> {
            long total = Message.countInRoom(conn, room.id());
            String nextKey = null;
            long nextId = 0;
            if (!page.isEmpty()) {
                Message edge = after != null ? page.getLast() : page.getFirst();
                boolean more = after != null ? Message.existsAfter(conn, room.id(), edge) : Message.existsBefore(conn, room.id(), edge);
                if (more) {
                    nextKey = after != null ? "after" : "before";
                    nextId = edge.id();
                }
            }
            String json = BotJson.messages(new MessagePresenter(conn, v), v, page, baseUrl, new UserCache(conn));
            return new Page(json, total, nextKey, nextId);
        });
    }

    public String messageJson(View v, Message message, String baseUrl) {
        return db.read(conn -> BotJson.message(new MessagePresenter(conn, v), v, message, baseUrl, new UserCache(conn)));
    }

    public String boostJson(View v, Boost boost, Message message, String baseUrl) {
        return db.read(conn -> BotJson.boost(v, boost, message, baseUrl, new UserCache(conn)));
    }

    /// A message of one of the user's rooms, by id parameters.
    public Optional<Message> messageInRoom(User user, @Nullable String roomId, @Nullable String messageId) {
        Long room = Params.parseId(roomId);
        Long id = Params.parseId(messageId);
        if (room == null || id == null) return Optional.empty();
        return Optional.ofNullable(db.read(conn -> {
            if (Room.findForUser(conn, user.id(), room) == null) return null;
            Message message = Message.find(conn, id);
            return message != null && message.roomId() == room ? message : null;
        }));
    }
}
