package campfire.bots;

import campfire.db.Times;
import campfire.messages.Boost;
import campfire.messages.Message;
import campfire.messages.MessagePresenter;
import campfire.rails.Messages;
import campfire.users.User;
import campfire.users.UserCache;
import campfire.view.FragmentCache;
import campfire.view.View;
import campfire.web.Paths;
import io.vertx.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The JSON bots read: messages and boosts, with absolute URLs for `baseUrl`. Each record's JSON is
/// rendered once per version and base URL, and repeated as first rendered (so a creator renamed
/// since shows under the old name until the message changes). Keys are in the order bots see them.
@NullMarked
final class BotJson {
    private BotJson() {}

    static String messages(MessagePresenter presenter, View v, List<Message> messages, String baseUrl, UserCache users) {
        var out = new StringJoiner(",", "[", "]");
        for (Message message : messages) out.add(message(presenter, v, message, baseUrl, users));
        return out.toString();
    }

    static String message(MessagePresenter presenter, View v, Message message, String baseUrl, UserCache users) {
        String key = "bots/message/" + message.id() + "-" + Times.toDb(message.updatedAt()) + "/" + baseUrl;
        return FragmentCache.fetch(key, () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("plain_text", presenter.plainTextBody(message));
            body.put("html", presenter.bodyHtml(message));
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", message.id());
            json.put("created_at", Messages.iso8601Millis(message.createdAt()));
            json.put("body", body);
            json.put("creator", user(v, users.find(message.creatorId()), baseUrl));
            json.put("room", Map.of("id", message.roomId()));
            json.put("url", baseUrl + Paths.roomMessage(message.roomId(), message.id()));
            return Json.encode(json).getBytes(StandardCharsets.UTF_8);
        }).toString();
    }

    static String boost(View v, Boost boost, Message message, String baseUrl, UserCache users) {
        String key = "bots/boost/" + boost.id() + "-" + Times.toDb(boost.updatedAt()) + "/" + baseUrl;
        return FragmentCache.fetch(key, () -> {
            Map<String, Object> messageJson = new LinkedHashMap<>();
            messageJson.put("id", boost.messageId());
            messageJson.put("url", baseUrl + Paths.roomMessage(message.roomId(), message.id()));
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", boost.id());
            json.put("content", boost.content());
            json.put("created_at", Messages.iso8601Millis(boost.createdAt()));
            json.put("booster", user(v, users.find(boost.boosterId()), baseUrl));
            json.put("message", messageJson);
            return Json.encode(json).getBytes(StandardCharsets.UTF_8);
        }).toString();
    }

    /// A deleted user fails the render, as it does in Rails.
    private static Map<String, Object> user(View v, @Nullable User user, String baseUrl) {
        if (user == null) throw new IllegalStateException("undefined method 'id' for nil");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", user.id());
        json.put("name", user.name());
        json.put("role", user.roleName());
        json.put("avatar_url", baseUrl + v.avatarPath(user));
        return json;
    }
}
