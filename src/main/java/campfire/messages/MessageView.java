package campfire.messages;

import campfire.db.Times;
import campfire.users.UserView;
import campfire.web.Paths;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// A message as its partial renders it. Dom ids use the client's id, so the optimistic copy the
/// composer inserted is the element the server's version replaces.
@NullMarked
public record MessageView(long id, String clientMessageId, long roomId, String roomName, UserView creator, Instant createdAt,
                          Instant updatedAt, boolean allEmoji, MessageContent content, List<BoostView> boosts) {
    public String domId(@Nullable String prefix) {
        return prefix == null || prefix.isEmpty() ? "message_" + clientMessageId : prefix + "_message_" + clientMessageId;
    }

    public boolean unrenderable() {
        return content instanceof MessageContent.Unrenderable;
    }

    public @Nullable AttachmentView attachment() {
        return content instanceof MessageContent.Attachment(AttachmentView a) ? a : null;
    }

    public String createdAtIso() {
        return Times.iso8601(createdAt);
    }

    public long createdAtEpoch() {
        return createdAt.toEpochMilli();
    }

    public long updatedAtEpoch() {
        return updatedAt.toEpochMilli();
    }

    public String atPath() {
        return Paths.roomAtMessage(roomId, id);
    }

    public String path() {
        return Paths.roomMessage(roomId, id);
    }

    public String editPath() {
        return Paths.editRoomMessage(roomId, id);
    }

    public String boostsPath() {
        return Paths.messageBoosts(id);
    }

    public String newBoostPath() {
        return Paths.newMessageBoost(id);
    }
}
