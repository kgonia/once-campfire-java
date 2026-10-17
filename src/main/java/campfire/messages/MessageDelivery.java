package campfire.messages;

import campfire.bots.WebhookService;
import campfire.push.MessagePusher;
import campfire.rooms.Room;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.NullMarked;

/// What follows a new message beyond the room's own stream, all in the background: push
/// notifications for every message, and for those posted by people or bots (not webhook
/// replies) delivery to the bots it concerns.
@ApplicationScoped
@NullMarked
public class MessageDelivery {
    @Inject
    MessagePusher pusher;
    @Inject
    WebhookService webhooks;

    public void posted(Room room, Message message) {
        pusher.pushLater(message);
        webhooks.deliverLater(room, message);
    }

    public void pushLater(Message message) {
        pusher.pushLater(message);
    }
}
