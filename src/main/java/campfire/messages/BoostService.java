package campfire.messages;

import campfire.cable.Broadcasts;
import campfire.db.Database;
import campfire.rooms.Room;
import campfire.users.User;
import campfire.users.UserView;
import campfire.view.View;
import campfire.web.Params;
import campfire.web.RecordNotFound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Boosts: short reactions (up to 16 characters) on a message. Adding or removing one touches the
/// message, and so its room, and is broadcast to the room. Shared by people and bots.
@ApplicationScoped
@NullMarked
public class BoostService {
    @Inject
    Database db;
    @Inject
    Broadcasts broadcasts;

    /// A message the boost form shows, with the booster it is shown to.
    public record Boosting(MessageView message, UserView booster) {}

    /// A message in one of the user's rooms by its id parameter, or a 404.
    public Message reachableMessage(User user, String messageId) {
        Long id = Params.parseId(messageId);
        return RecordNotFound.require(id == null ? null : db.read(conn -> Message.findReachable(conn, user.id(), id)));
    }

    public MessageView view(View v, Message message) {
        return db.read(conn -> new MessagePresenter(conn, v).view(message));
    }

    public Boosting boosting(View v, Message message, User booster) {
        return db.read(conn -> new Boosting(new MessagePresenter(conn, v).view(message), UserView.of(booster, v)));
    }

    /// The user's own boost of the message by its id parameter, or null.
    public @Nullable Boost findOwn(Message message, User booster, @Nullable String id) {
        Long boostId = Params.parseId(id);
        if (boostId == null) return null;
        Boost boost = db.read(conn -> Boost.find(conn, message.id(), boostId));
        return boost != null && boost.boosterId() == booster.id() ? boost : null;
    }

    /// Adds the boost and appends it to the message on everyone's screen. A missing `content`
    /// violates the column's NOT NULL, a 500 as in Rails.
    public Boost create(View detached, Message message, User booster, @Nullable String content) {
        if (content == null) throw new IllegalStateException("NOT NULL constraint failed: boosts.content");
        Boost boost = db.write(tx -> {
            Boost created = Boost.create(tx, message.id(), booster.id(), content);
            Message current = Message.find(tx, message.id());
            if (current != null) Message.touch(tx, current, null);
            return created;
        });
        db.read(conn -> {
            String html = MessageFragments.boost(detached, new MessagePresenter(conn, detached).boostView(boost, booster)).toString();
            Room room = Room.find(conn, message.roomId());
            if (room == null) throw new IllegalStateException("undefined method 'room' for nil");
            broadcasts.boostCreate(room, message, html);
            return null;
        });
        return boost;
    }

    public void delete(Message message, Boost boost) {
        Room room = db.write(tx -> {
            Boost.delete(tx, boost.id());
            Message current = Message.find(tx, message.id());
            if (current != null) Message.touch(tx, current, null);
            return Room.find(tx, message.roomId());
        });
        if (room == null) throw new IllegalStateException("undefined method 'room' for nil");
        broadcasts.boostRemove(room, boost);
    }
}
