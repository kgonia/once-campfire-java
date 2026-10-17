package campfire;

import campfire.assets.Assets;
import campfire.cable.Broadcasts;
import campfire.cable.Cable;
import campfire.db.Database;
import campfire.integrations.webpush.WebPush;
import campfire.push.PushSubscription;
import campfire.rails.Secrets;
import campfire.rails.SecureRandoms;
import campfire.storage.Storage;
import campfire.web.ResponseCache;
import campfire.sessions.SessionLookup;
import io.quarkus.logging.Log;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// The long-lived services, built from {@link Config}. They stay plain classes so tests can build
/// them without a container.
public class Infrastructure {
    @Produces
    @ApplicationScoped
    Database database(Config config) {
        return new Database(config.databasePath(), config.readers());
    }

    void close(@Disposes Database db) {
        db.close();
    }

    /// Without `SECRET_KEY_BASE` a secret is generated once and kept in storage, so sessions and
    /// signed URLs survive restarts. `SECRET_KEY_BASE_DUMMY` (asset builds) gets a throwaway one.
    @Produces
    @ApplicationScoped
    Secrets secrets(Config config) {
        String secret = config.secretKeyBase().filter(s -> !s.isBlank()).orElseGet(() ->
            System.getenv("SECRET_KEY_BASE_DUMMY") != null ? SecureRandoms.hex(64) : storedSecret(config.storage()));
        return new Secrets(secret);
    }

    private static String storedSecret(Path storage) {
        Path file = storage.resolve("secret_key_base");
        try {
            if (Files.exists(file)) return Files.readString(file).strip();
            String secret = SecureRandoms.hex(64);
            Files.createDirectories(storage);
            Files.writeString(file, secret);
            return secret;
        } catch (IOException e) {
            throw new UncheckedIOException("SECRET_KEY_BASE is unset and " + file + " can't be written", e);
        }
    }

    @Produces
    @ApplicationScoped
    Assets assets() {
        return new Assets();
    }

    @Produces
    @ApplicationScoped
    Storage storage(Config config, Secrets secrets, Database db) {
        return new Storage(config, secrets, db);
    }

    void close(@Disposes Storage storage) {
        storage.close();
    }

    @Produces
    @ApplicationScoped
    SessionLookup sessions(Database db) {
        return new SessionLookup(db);
    }

    @Produces
    @ApplicationScoped
    ResponseCache responses(Config config) {
        return new ResponseCache(config.responseCacheBytes());
    }

    /// Disabled (sends nothing) without both VAPID keys. Subscriptions the push service reports as
    /// gone are deleted.
    @Produces
    @ApplicationScoped
    WebPush webPush(Config config, Database db) {
        var push = new WebPush(config.vapidPublicKey().orElse(null), config.vapidPrivateKey().orElse(null), config.vapidSubject());
        push.setInvalidSubscriptionHandler(id -> {
            Log.infof("Destroying push subscription: %d", id);
            db.write(tx -> {
                PushSubscription.delete(tx, id);
                return null;
            });
        });
        return push;
    }

    void close(@Disposes WebPush push) {
        push.close();
    }

    @Produces
    @ApplicationScoped
    Cable cable(App app) {
        return new Cable(app);
    }

    void close(@Disposes Cable cable) {
        cable.close();
    }

    @Produces
    @ApplicationScoped
    Broadcasts broadcasts(Cable cable) {
        return new Broadcasts(cable);
    }
}
