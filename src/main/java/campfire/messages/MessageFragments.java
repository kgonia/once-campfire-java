package campfire.messages;

import campfire.db.Times;
import campfire.view.FragmentCache;
import campfire.view.Pages;
import campfire.view.Safe;
import campfire.view.View;
import gg.jte.generated.precompiled.messages.Jte_messageGenerated;
import gg.jte.generated.precompiled.messages.Jte_presentationGenerated;
import gg.jte.generated.precompiled.messages.boosts.Jte_boostGenerated;
import java.time.Instant;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The message and boost partials, rendered once per record version. Message keys carry the host
/// the page is served on, since link previews only embed URLs of other hosts.
@NullMarked
public final class MessageFragments {
    private MessageFragments() {}

    public static @Nullable Safe cachedMessage(Message message, String host) {
        return FragmentCache.read(messageKey(message.id(), message.updatedAt(), host));
    }

    public static Safe message(View v, MessageView message) {
        return FragmentCache.fetch(messageKey(message.id(), message.updatedAt(), host(v)),
            () -> Pages.bytes(v, (out, view) -> Jte_messageGenerated.render(out, null, view, message)));
    }

    public static Safe boost(View v, BoostView boost) {
        return FragmentCache.fetch("b/" + boost.id() + "-" + Times.toDb(boost.updatedAt()),
            () -> Pages.bytes(v, (out, view) -> Jte_boostGenerated.render(out, null, view, boost)));
    }

    /// The body alone, as an edit replaces it on everyone's screen. Not cached: the edit has just
    /// changed it.
    public static Safe presentation(View v, MessageView message) {
        return Safe.bytes(Pages.bytes(v, (out, view) -> Jte_presentationGenerated.render(out, null, view, message)));
    }

    /// The base URL without its scheme: `host[:port]`.
    public static String host(View v) {
        String base = v.baseUrl;
        int scheme = base.indexOf("://");
        return scheme < 0 ? base : base.substring(scheme + 3);
    }

    private static String messageKey(long id, Instant updatedAt, String host) {
        return "m/" + id + "-" + Times.toDb(updatedAt) + "/" + host;
    }
}
