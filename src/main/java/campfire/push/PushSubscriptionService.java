package campfire.push;

import campfire.db.Database;
import campfire.integrations.net.Network;
import campfire.integrations.webpush.WebPush;
import campfire.rooms.Membership;
import campfire.web.RecordNotFound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A user's Web Push subscriptions: one per browser, registered by the browser itself.
@ApplicationScoped
@NullMarked
public class PushSubscriptionService {
    /// The subscription fields a browser registers.
    static final List<String> FIELDS = List.of("endpoint", "p256dh_key", "auth_key");

    @Inject
    Database db;
    @Inject
    WebPush webPush;

    public List<PushSubscriptionView> list(long userId) {
        return db.read(conn -> PushSubscription.forUser(conn, userId)).stream().map(PushSubscriptionView::of).toList();
    }

    /// Registers a subscription, or refreshes the matching one (`fields` holds only the fields
    /// given; a null value matches a NULL column). False when the endpoint isn't a permitted push
    /// service with a public address, checked again for a subscription already saved.
    public boolean register(long userId, Map<String, @Nullable String> fields, @Nullable String userAgent) {
        PushSubscription existing = db.read(conn -> {
            Long id = PushSubscription.idMatching(conn, userId, fields);
            return id == null ? null : PushSubscription.find(conn, userId, id);
        });
        String endpoint = existing != null ? existing.endpoint() : fields.get("endpoint");
        if (!WebPush.endpointErrors(endpoint, Network.SYSTEM_RESOLVER).isEmpty()) return false;
        db.write(tx -> {
            if (existing != null) PushSubscription.touch(tx, existing.id());
            else PushSubscription.create(tx, userId, endpoint, fields.get("p256dh_key"), fields.get("auth_key"), userAgent);
            return null;
        });
        return true;
    }

    /// Ids of other users' subscriptions are ignored.
    public void delete(long userId, long id) {
        db.write(tx -> {
            PushSubscription.deleteForUser(tx, userId, id, null);
            return null;
        });
    }

    /// Sends a notification titled "Campfire Test" with a random body, now, letting delivery
    /// errors through. `path` is where tapping it goes.
    public void sendTest(long userId, long id, String path) {
        record Target(PushSubscription subscription, long badge) {}
        Target target = db.read(conn -> {
            PushSubscription subscription = RecordNotFound.require(PushSubscription.find(conn, userId, id));
            return new Target(subscription, Membership.unreadCount(conn, userId));
        });
        var subscription = target.subscription();
        var result = webPush.deliverNow(new WebPush.Subscription(subscription.id(), subscription.endpoint(), subscription.p256dhKey(), subscription.authKey()),
            "Campfire Test", UUID.randomUUID().toString(), path, target.badge(), WebPush.ACCOUNT_LOGO_PATH);
        if (result instanceof WebPush.Result.Failed(var error)) throw new IllegalStateException(error.getMessage(), error);
    }
}
