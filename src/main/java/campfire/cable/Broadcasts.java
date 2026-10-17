package campfire.cable;

import campfire.db.Conn;
import campfire.messages.Boost;
import campfire.rooms.Membership;
import campfire.messages.Message;
import campfire.rooms.Room;
import campfire.rails.GlobalId;
import campfire.rails.Json;
import campfire.richtext.Html;
import java.util.Map;

/// The broadcasts the app makes (`Message::Broadcasts`, the rooms controllers' `broadcast_*_to`),
/// as Turbo stream tags on Action Cable streams. Callers render the partials and pass the HTML.
public final class Broadcasts {
    public static final String ROOMS = "rooms";
    public static final String MESSAGES = "messages";

    /// Where broadcasts go: the cable server's pubsub (`ActionCable.server.broadcast`).
    @FunctionalInterface
    public interface PubSub {
        /// Sends `json` (an encoded message) to every subscriber of `stream`.
        void broadcast(String stream, String json);
    }

    private final PubSub pubsub;

    public Broadcasts(PubSub pubsub) {
        this.pubsub = pubsub;
    }

    // -- Names -------------------------------------------------------------------------------------

    /// A room's GlobalID names its STI class (`gid://campfire/Rooms::Open/1`).
    public static GlobalId roomGid(Room room) {
        return GlobalId.of(room.type(), room.id());
    }

    public static GlobalId userGid(long userId) {
        return GlobalId.of("User", userId);
    }

    /// `Turbo::StreamsChannel`'s stream name: the streamables' `to_gid_param` or `to_param`, joined by ":".
    public static String streamName(String... streamables) {
        return String.join(":", streamables);
    }

    public static String roomMessages(Room room) {
        return streamName(roomGid(room).toParam(), MESSAGES);
    }

    public static String userRooms(long userId) {
        return streamName(userGid(userId).toParam(), ROOMS);
    }

    /// `dom_id(room, prefix)`: the STI class's param key (`rooms_open_1`).
    public static String roomDomId(Room room, String prefix) {
        String key = room.type().replace("::", "_").toLowerCase(java.util.Locale.ROOT) + "_" + room.id();
        return prefix == null ? key : prefix + "_" + key;
    }

    /// `dom_id(message, prefix)`: messages are keyed by `client_message_id`.
    public static String messageDomId(Message message, String prefix) {
        return prefix == null ? "message_" + message.clientMessageId() : prefix + "_message_" + message.clientMessageId();
    }

    // -- Turbo stream tags ---------------------------------------------------------------------------

    /// `turbo_stream_action_tag(action, target:, template:, **attributes)`: extra attributes first.
    public static String actionTag(String action, String target, String template, String... attributes) {
        var tag = new StringBuilder("<turbo-stream");
        for (int i = 0; i + 1 < attributes.length; i += 2) attribute(tag, attributes[i], attributes[i + 1]);
        attribute(tag, "action", action);
        if (target != null) attribute(tag, "target", target);
        tag.append('>');
        if (!action.equals("remove") && !action.equals("refresh")) tag.append("<template>").append(template == null ? "" : template).append("</template>");
        return tag.append("</turbo-stream>").toString();
    }

    private static void attribute(StringBuilder tag, String name, String value) {
        if (value != null) tag.append(' ').append(name).append("=\"").append(Html.escape(value)).append('"');
    }

    /// `Turbo::StreamsChannel.broadcast_stream_to`
    public void streamTo(String stream, String tag) {
        pubsub.broadcast(stream, Json.encode(tag));
    }

    public void broadcast(String stream, Object payload) {
        pubsub.broadcast(stream, Json.encode(payload));
    }

    // -- Message::Broadcasts -------------------------------------------------------------------------

    /// `message.broadcast_create`: append to the room, then tell every member's unread stream.
    public void messageCreate(Conn conn, Room room, Message message, String html) {
        streamTo(roomMessages(room), actionTag("append", roomDomId(room, MESSAGES), html));
        unreadRoom(conn, room);
    }

    /// `broadcast_unread_room`: `{ roomId: }` to each member's `user_<id>_unreads`.
    public void unreadRoom(Conn conn, Room room) {
        String payload = Json.encode(Map.of("roomId", room.id()));
        for (Membership m : Membership.forRoom(conn, room.id())) pubsub.broadcast("user_" + m.userId() + "_unreads", payload);
    }

    /// `ActionCable.server.broadcast "user_#{id}_reads", { room_id: }`
    public void readRoom(long userId, long roomId) {
        pubsub.broadcast("user_" + userId + "_reads", Json.encode(Map.of("room_id", roomId)));
    }

    public void messageRemove(Room room, Message message) {
        streamTo(roomMessages(room), actionTag("remove", messageDomId(message, null), null));
    }

    /// MessagesController#update: replace `[message, :presentation]`, keeping the scroll position.
    public void messageReplace(Room room, Message message, String presentationHtml) {
        streamTo(roomMessages(room), actionTag("replace", messageDomId(message, "presentation"), presentationHtml, "maintain_scroll", "true"));
    }

    public void boostCreate(Room room, Message message, String boostHtml) {
        streamTo(roomMessages(room), actionTag("append", "boosts_message_" + message.clientMessageId(), boostHtml, "maintain_scroll", "true"));
    }

    public void boostRemove(Room room, Boost boost) {
        streamTo(roomMessages(room), actionTag("remove", "boost_" + boost.id(), null));
    }

    // -- The sidebar's room lists --------------------------------------------------------------------

    public void roomRemove(Room room) {
        streamTo(ROOMS, actionTag("remove", roomDomId(room, "list"), null));
    }

    public void openRoomCreate(String sharedRoomHtml) {
        streamTo(ROOMS, actionTag("prepend", "shared_rooms", sharedRoomHtml));
    }

    /// `room` as an open room (`becomes!(Rooms::Open)`).
    public void openRoomUpdate(Room room, String sharedRoomHtml) {
        streamTo(ROOMS, actionTag("replace", roomDomId(room, "list"), sharedRoomHtml));
    }

    public void closedRoomCreate(Conn conn, Room room, String sharedRoomHtml) {
        for (long userId : Room.userIds(conn, room.id())) streamTo(userRooms(userId), actionTag("prepend", "shared_rooms", sharedRoomHtml));
    }

    public void closedRoomUpdate(Conn conn, Room room, String sharedRoomHtml) {
        String target = roomDomId(room, "list");
        for (long userId : Room.userIds(conn, room.id())) streamTo(userRooms(userId), actionTag("replace", target, sharedRoomHtml));
    }

    /// Rooms::DirectsController#create: `users/sidebars/rooms/_direct`, rendered per membership.
    public void directRoomCreate(Membership membership, String directRoomHtml) {
        streamTo(userRooms(membership.userId()), actionTag("prepend", "direct_rooms", directRoomHtml));
    }

    /// Rooms::InvolvementsController#update (`broadcast_visibility_changes`).
    public void involvementChange(Room room, Membership membership, String previous, java.util.function.Supplier<String> sharedRoomHtml) {
        if (room.isDirect()) return;
        String stream = userRooms(membership.userId());
        if ("invisible".equals(membership.involvement())) {
            streamTo(stream, actionTag("remove", roomDomId(room, "list"), null));
        } else if (previous == null) {
            throw new IllegalStateException("undefined method 'inquiry' for nil");
        } else if (previous.equals("invisible")) {
            streamTo(stream, actionTag("prepend", "shared_rooms", sharedRoomHtml.get()));
        }
    }
}
