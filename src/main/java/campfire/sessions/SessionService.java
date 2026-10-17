package campfire.sessions;

import campfire.accounts.HelpContact;
import campfire.db.Database;
import campfire.push.PushSubscription;
import campfire.rails.Secrets;
import campfire.users.User;
import campfire.users.UserPages;
import campfire.users.Users;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Signing in with a password or a transfer link, and what the sign-in page shows.
@ApplicationScoped
@NullMarked
public class SessionService {
    /// Sign-in attempts allowed per client address within {@link #RATE_LIMIT_WINDOW}.
    static final int RATE_LIMIT = 10;
    static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(3);

    @Inject
    Database db;
    @Inject
    Secrets secrets;

    private record Counter(int count, Instant expiresAt) {}

    /// Attempts per address. A window opens with the first attempt and doesn't slide.
    private final Map<String, Counter> attempts = new HashMap<>();

    /// Counts an attempt from `ip`; true once there were more than {@link #RATE_LIMIT}.
    public synchronized boolean rateLimited(String ip, Instant now) {
        attempts.values().removeIf(counter -> !counter.expiresAt().isAfter(now));
        Counter counter = attempts.merge(ip, new Counter(1, now.plus(RATE_LIMIT_WINDOW)),
            (current, first) -> new Counter(current.count() + 1, current.expiresAt()));
        return counter.count() > RATE_LIMIT;
    }

    /// The active user with this email and password, or null. Hashing the password takes a
    /// quarter of a second and holds no connection.
    public @Nullable User authenticate(String emailAddress, String password) {
        return Users.authenticate(db, emailAddress, password);
    }

    /// The active user a transfer link (valid for four hours) signs in, or null.
    public @Nullable User transferredUser(String transferId) {
        Long userId = secrets.verifySignedId("User", transferId, "transfer", Instant.now());
        if (userId == null) return null;
        User user = db.read(conn -> User.find(conn, userId));
        return user != null && user.isActive() ? user : null;
    }

    public static String transferId(Secrets secrets, long userId) {
        return UserPages.transferId(secrets, userId);
    }

    /// No one has signed up yet: the account still needs its first run.
    public boolean noUsers() {
        return db.read(User::none);
    }

    public @Nullable HelpContact helpContact() {
        return db.read(HelpContact::find);
    }

    /// Signing out on a device stops its push notifications.
    public void forgetPushSubscription(long userId, String endpoint) {
        db.write(tx -> {
            PushSubscription.deleteForUser(tx, userId, null, endpoint);
            return null;
        });
    }
}
