package campfire.bots;

import campfire.accounts.AccountService;
import campfire.db.Database;
import campfire.rooms.Room;
import campfire.storage.AttachmentChange;
import campfire.storage.Blob;
import campfire.storage.Storage;
import campfire.users.User;
import campfire.users.Users;
import campfire.web.Params;
import campfire.web.RecordNotFound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Setting up the account's bots: each has a name, an avatar, an optional webhook, and a key
/// (`<id>-<token>`) that its API calls carry. Removing a bot deactivates it.
@ApplicationScoped
@NullMarked
public class BotAdminService {
    @Inject
    Database db;
    @Inject
    Storage storage;
    @Inject
    AccountService accounts;

    /// A bot with the shared rooms it posts to, by name.
    public record Listed(User bot, List<Room> rooms) {}

    /// The edit form's values. `avatarUrl` is the uploaded avatar's absolute URL, or null.
    public record Form(@Nullable String name, @Nullable String webhookUrl, @Nullable String avatarUrl) {
        public static final Form NEW = new Form(null, null, null);
    }

    public List<Listed> bots() {
        return db.read(conn -> User.activeBotsOrdered(conn).stream().map(bot -> new Listed(bot, Room.sharedForUserOrdered(conn, bot.id()))).toList());
    }

    /// An active bot by id parameter, or a 404.
    public User bot(@Nullable String id) {
        Long botId = Params.parseId(id);
        return RecordNotFound.require(botId == null ? null : db.read(conn -> User.findActiveBot(conn, botId)));
    }

    public Form form(User bot, String baseUrl) {
        return db.read(conn -> {
            Webhook webhook = Webhook.forUser(conn, bot.id());
            Blob avatar = storage.attached("User", bot.id(), "avatar");
            return new Form(bot.name(), webhook == null ? null : webhook.url(), avatar == null ? null : baseUrl + storage.blobPath(avatar));
        });
    }

    /// A missing name fails like the NOT NULL column it is.
    public void create(@Nullable String name, @Nullable String webhookUrl, AttachmentChange avatar) {
        if (name == null) throw new IllegalStateException("NOT NULL constraint failed: users.name");
        try (var staged = avatar.stage(storage)) {
            db.write(tx -> {
                User bot = Users.createBot(tx, name, webhookUrl);
                staged.apply(tx, "User", "users", bot.id(), "avatar");
                return null;
            });
        }
    }

    public void update(User bot, @Nullable String name, @Nullable String webhookUrl, AttachmentChange avatar) {
        try (var staged = avatar.stage(storage)) {
            db.write(tx -> {
                Users.updateBot(tx, bot, name, webhookUrl);
                staged.apply(tx, "User", "users", bot.id(), "avatar");
                return null;
            });
        }
    }

    /// The old key stops working at once.
    public void resetKey(User bot) {
        db.write(tx -> {
            Users.resetBotKey(tx, bot.id());
            return null;
        });
    }

    public void deactivate(User bot) {
        accounts.deactivate(bot);
    }
}
