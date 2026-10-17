package campfire.messages;

import campfire.users.UserView;
import campfire.web.Paths;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record BoostView(long id, Instant updatedAt, long messageId, String content, boolean allEmoji, UserView booster) {
    /// The one-tap boosts offered in a message's menu, in menu order.
    public record Reaction(String emoji, String label) {}

    public static final List<Reaction> QUICK_REACTIONS = List.of(new Reaction("👍", "Thumbs up"), new Reaction("👏", "Clapping"),
        new Reaction("👋", "Waving hand"), new Reaction("💪", "Muscle"), new Reaction("❤️", "Red heart"),
        new Reaction("😂", "Face with tears of joy"), new Reaction("🎉", "Party popper"), new Reaction("🔥", "Fire"));

    public String domId() {
        return "boost_" + id;
    }

    public String path() {
        return Paths.messageBoost(messageId, id);
    }
}
