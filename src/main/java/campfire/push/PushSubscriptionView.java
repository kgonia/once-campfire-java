package campfire.push;

import campfire.useragent.UserAgent;
import org.jspecify.annotations.NullMarked;

/// A push subscription as the settings page lists it, with the browser it came from.
@NullMarked
public record PushSubscriptionView(long id, String endpoint, String browser, String version, String platform) {
    public static PushSubscriptionView of(PushSubscription subscription) {
        UserAgent agent = UserAgent.parse(subscription.userAgent() == null ? "" : subscription.userAgent());
        return new PushSubscriptionView(subscription.id(), subscription.endpoint() == null ? "" : subscription.endpoint(), agent.browser(),
            agent.version(), agent.platform());
    }
}
