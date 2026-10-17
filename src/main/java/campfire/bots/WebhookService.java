package campfire.bots;

import campfire.App;
import campfire.db.Database;
import campfire.integrations.net.Network;
import campfire.messages.Message;
import campfire.messages.MessagePresenter;
import campfire.messages.MessageService;
import campfire.rails.Json;
import campfire.richtext.RichText;
import campfire.richtext.RichTextRecord;
import campfire.rooms.Room;
import campfire.storage.Storage;
import campfire.users.User;
import campfire.view.View;
import campfire.web.Paths;
import io.quarkus.logging.Log;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NullMarked;

/// Tells bots about messages that concern them and posts what they answer. A message concerns
/// every active bot in a direct room, and elsewhere the active bots it mentions; never its own
/// creator. Each bot is delivered to separately, in the background; a failed delivery is logged.
@ApplicationScoped
@NullMarked
public class WebhookService {
    @Inject
    Database db;
    @Inject
    App app;
    @Inject
    Storage storage;
    @Inject
    MessageService messages;
    @Inject
    Network net;

    private final ExecutorService jobs = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("campfire-webhook-", 0).factory());

    public void deliverLater(Room room, Message message) {
        View v = detachedView();
        List<User> bots = db.read(conn -> {
            List<User> candidates = room.isDirect() ? User.activeBotsInRoom(conn, room.id()) : new MessagePresenter(conn, v).mentionees(message);
            return candidates.stream().filter(user -> user.isBot() && user.isActive() && user.id() != message.creatorId()).toList();
        });
        for (User bot : bots) {
            jobs.execute(() -> {
                try {
                    deliver(bot.id(), message.id());
                } catch (Exception e) {
                    Log.errorf(e, "Delivering message %d to bot %d failed", message.id(), bot.id());
                }
            });
        }
    }

    private void deliver(long botId, long messageId) throws WebhookClient.DeliveryException {
        View v = detachedView();
        record Delivery(User bot, Room room, String url, String payload) {}
        Delivery delivery = db.read(conn -> {
            User bot = User.find(conn, botId);
            Message message = Message.find(conn, messageId);
            if (bot == null || message == null) throw new IllegalStateException("the bot or the message is gone");
            Room room = Room.find(conn, message.roomId());
            Webhook webhook = Webhook.forUser(conn, botId);
            if (webhook == null) throw new IllegalStateException("undefined method 'deliver' for nil (the bot has no webhook)");
            User creator = User.find(conn, message.creatorId());
            var presenter = new MessagePresenter(conn, v);
            var body = RichTextRecord.find(conn, Message.RECORD_TYPE, message.id(), "body");
            // Key order as Rails writes the payload hash.
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", creator.id());
            user.put("name", creator.name());
            Map<String, Object> roomJson = new LinkedHashMap<>();
            roomJson.put("id", room.id());
            roomJson.put("name", room.name());
            roomJson.put("path", Paths.roomBotMessages(room.id(), bot.botKey()));
            Map<String, Object> bodyJson = new LinkedHashMap<>();
            bodyJson.put("html", body == null ? null : body.body());
            bodyJson.put("plain", RichText.withoutRecipientMentions(presenter.plainTextBody(message), bot.name()));
            Map<String, Object> messageJson = new LinkedHashMap<>();
            messageJson.put("id", message.id());
            messageJson.put("body", bodyJson);
            messageJson.put("path", Paths.roomAtMessage(room.id(), message.id()));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user", user);
            payload.put("room", roomJson);
            payload.put("message", messageJson);
            return new Delivery(bot, room, webhook.url() == null ? "" : webhook.url(), Json.encode(payload));
        });
        switch (WebhookClient.deliver(net, delivery.url(), delivery.payload())) {
            case WebhookClient.Reply.None none -> {}
            case WebhookClient.Reply.Text(String text) -> messages.postReply(v, delivery.room(), delivery.bot(), text, null);
            case WebhookClient.Reply.File(byte[] data, String filename, String contentType) -> {
                Storage.Staged staged = storage.stage(new ByteArrayInputStream(data), filename, contentType);
                messages.postReply(v, delivery.room(), delivery.bot(), null, staged);
            }
        }
    }

    /// Replies are rendered outside any request, where Rails' renderer assumes this host.
    private View detachedView() {
        var account = app.account();
        return View.detached(app, account.account(), account.hasLogo(), "http://example.org");
    }

    @PreDestroy
    void close() {
        jobs.shutdown();
    }
}
