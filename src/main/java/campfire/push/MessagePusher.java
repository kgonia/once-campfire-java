package campfire.push;

import campfire.App;
import campfire.db.Database;
import campfire.db.Times;
import campfire.integrations.webpush.WebPush;
import campfire.messages.Message;
import campfire.messages.MessagePresenter;
import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.users.User;
import campfire.view.View;
import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NullMarked;

/// Notifies a new message to the room's members who aren't looking at it: those involved in
/// everything, and those involved in mentions whom it mentions. A direct room's notification is
/// titled with the sender; others with the room, the body prefixed by the sender's name. The badge
/// is each recipient's unread room count.
@ApplicationScoped
@NullMarked
public class MessagePusher {
    @Inject
    Database db;
    @Inject
    App app;
    @Inject
    WebPush webPush;

    private final ExecutorService jobs = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("campfire-push-", 0).factory());

    public void pushLater(Message message) {
        if (!webPush.enabled()) return;
        long id = message.id();
        jobs.execute(() -> {
            try {
                push(id);
            } catch (RuntimeException e) {
                Log.errorf(e, "Pushing message %d failed", id);
            }
        });
    }

    private void push(long messageId) {
        var account = app.account();
        // Rendered outside any request, like Rails' default renderer.
        View v = View.detached(app, account.account(), account.hasLogo(), "http://example.org");
        db.read(conn -> {
            Message message = Message.find(conn, messageId);
            if (message == null) return null;
            Room room = Room.find(conn, message.roomId());
            User creator = User.find(conn, message.creatorId());
            if (room == null || creator == null) return null;
            var presenter = new MessagePresenter(conn, v);
            String body = presenter.plainTextBody(message);
            String title = room.isDirect() ? creator.name() : room.name() == null ? "" : room.name();
            if (!room.isDirect()) body = creator.name() + ": " + body;
            List<Long> mentioned = presenter.mentionees(message).stream().map(User::id).toList();
            String path = "/rooms/" + room.id();
            for (PushSubscription subscription : PushSubscription.forRoomDelivery(conn, room.id(), creator.id(), mentioned, Times.now())) {
                long badge = Membership.unreadCount(conn, subscription.userId());
                webPush.deliverLater(new WebPush.Subscription(subscription.id(), subscription.endpoint(), subscription.p256dhKey(), subscription.authKey()),
                    title, body, path, badge, WebPush.ACCOUNT_LOGO_PATH);
            }
            return null;
        });
    }

    @PreDestroy
    void close() {
        jobs.shutdown();
    }
}
