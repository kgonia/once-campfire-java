package campfire.cable;

import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.rails.GlobalId;
import campfire.rails.Json;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Campfire's channels (reference/app/channels) and `Turbo::StreamsChannel` with
/// `RoomStreamsAreAuthorized` prepended: identifiers, streams, payloads and callback order
/// (`on_subscribe` runs after `subscribed`, before the confirmation).
final class Channels {
    private Channels() {}

    static final String STREAMS_CHANNEL = "Turbo::StreamsChannel";

    /// Registers every channel under its Ruby class name, which is what clients put in `channel`.
    static void register(Cable cable) {
        cable.register("ApplicationCable::Channel", Channel.Empty::new);
        cable.register("HeartbeatChannel", Channel.Empty::new);
        cable.register("PresenceChannel", PresenceChannel::new);
        cable.register("ReadRoomsChannel", ReadRoomsChannel::new);
        cable.register("RoomChannel", RoomChannel::new);
        cable.register("RoomMessagesChannel", RoomMessagesChannel::new);
        cable.register("TypingNotificationsChannel", TypingNotificationsChannel::new);
        cable.register("UnreadRoomsChannel", UnreadRoomsChannel::new);
        cable.register(STREAMS_CHANNEL, StreamsChannel::new);
    }

    // -- RoomChannel ----------------------------------------------------------------------------------

    /// `RoomChannel`, and the room lookup `PresenceChannel` and `TypingNotificationsChannel` inherit.
    /// Null `room` means `subscribed` failed, and the actions then do nothing.
    static class RoomChannel implements Channel {
        Room room;

        /// `stream_for @room` names the broadcasting after the channel class: `room:<gid param>`.
        String broadcasting(Room room) {
            return "room:" + Broadcasts.roomGid(room).toParam();
        }

        /// `current_user.rooms.find_by(id: params[:room_id])`, streamed if found, else rejected.
        @Override
        public void subscribed(Subscription sub) {
            Long roomId = id(sub.param("room_id"));
            long userId = sub.currentUser().id();
            room = roomId == null ? null : sub.cable().db().read(conn -> Room.findForUser(conn, userId, roomId));
            if (room != null) sub.streamFrom(broadcasting(room));
            else sub.reject();
        }
    }

    /// The client sends `room_id` as a JSON number; a decimal string is taken too.
    static Long id(Object value) {
        return switch (value) {
            case Long id -> id;
            case String text -> {
                try {
                    yield Long.parseLong(text);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }

    // -- PresenceChannel --------------------------------------------------------------------------------

    /// A `RoomChannel` that marks the membership connected while subscribed
    /// (`Membership::Connectable`) and tells the user's other windows the room has been read.
    static final class PresenceChannel extends RoomChannel {
        private interface Change {
            void apply(campfire.db.Database.Tx tx, long roomId, long userId);
        }

        @Override
        String broadcasting(Room room) {
            return "presence:" + Broadcasts.roomGid(room).toParam();
        }

        /// `subscribed`, then `on_subscribe :present, unless: :subscription_rejected?`.
        @Override
        public void subscribed(Subscription sub) {
            super.subscribed(sub);
            if (!sub.rejected()) present(sub);
        }

        /// `on_unsubscribe :absent, unless: :subscription_rejected?`.
        @Override
        public void unsubscribed(Subscription sub) {
            if (!sub.rejected()) change(sub, Membership::disconnected);
        }

        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (sub.rejected()) return false;
            switch (action) {
                case "present" -> present(sub);
                case "absent" -> change(sub, Membership::disconnected);
                case "refresh" -> change(sub, Membership::refreshConnection);
                default -> {
                    return false;
                }
            }
            return true;
        }

        /// `membership.present`, then `broadcast_read_room`
        /// (`ActionCable.server.broadcast "user_#{id}_reads", { room_id: membership.room_id }`).
        private void present(Subscription sub) {
            if (!change(sub, Membership::present)) return;
            sub.cable().deliver(ReadRoomsChannel.streamNameFor(sub.currentUser().id()), Json.encode(Map.of("room_id", room.id())));
        }

        /// Applies `change` to the user's membership; false when there's no longer one.
        private boolean change(Subscription sub, Change change) {
            if (room == null) return false;
            long roomId = room.id(), userId = sub.currentUser().id();
            return sub.cable().db().write(tx -> {
                if (Membership.find(tx, roomId, userId) == null) return false;
                change.apply(tx, roomId, userId);
                return true;
            });
        }
    }

    // -- TypingNotificationsChannel -------------------------------------------------------------------

    static final class TypingNotificationsChannel extends RoomChannel {
        @Override
        String broadcasting(Room room) {
            return "typing_notifications:" + Broadcasts.roomGid(room).toParam();
        }

        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (sub.rejected() || !(action.equals("start") || action.equals("stop"))) return false;
            if (room != null) broadcast(action, sub);
            return true;
        }

        /// `broadcast_to @room, action:, user: current_user.slice(:id, :name)`.
        private void broadcast(String action, Subscription sub) {
            var user = new LinkedHashMap<String, Object>();
            user.put("id", sub.currentUser().id());
            user.put("name", sub.currentUser().name());
            var payload = new LinkedHashMap<String, Object>();
            payload.put("action", action);
            payload.put("user", user);
            sub.cable().deliver(broadcasting(room), Json.encode(payload));
        }
    }

    // -- ReadRoomsChannel and UnreadRoomsChannel ------------------------------------------------------

    static final class ReadRoomsChannel implements Channel {
        /// The user's own stream of rooms read in another window.
        static String streamNameFor(long userId) {
            return "user_" + userId + "_reads";
        }

        @Override
        public void subscribed(Subscription sub) {
            sub.streamFrom(streamNameFor(sub.currentUser().id()));
        }
    }

    static final class UnreadRoomsChannel implements Channel {
        /// `UnreadRoomsChannel.stream_name_for(user_id)`: per user, so activity in a room only
        /// reaches its members.
        static String streamNameFor(long userId) {
            return "user_" + userId + "_unreads";
        }

        @Override
        public void subscribed(Subscription sub) {
            sub.streamFrom(streamNameFor(sub.currentUser().id()));
        }
    }

    // -- Signed stream names --------------------------------------------------------------------------

    /// `verified_stream_name_from_params`: `params[:signed_stream_name]` verified with
    /// `Turbo.signed_stream_verifier`, or null.
    static String verifiedStreamNameFromParams(Subscription sub) {
        return sub.param("signed_stream_name") instanceof String signed ? sub.cable().secrets().verifiedStreamName(signed) : null;
    }

    /// `Turbo::StreamsChannel` with `RoomStreamsAreAuthorized` prepended: a room's message stream
    /// is turned away here even when validly signed, so `RoomMessagesChannel` is the only door.
    static final class StreamsChannel implements Channel {
        @Override
        public void subscribed(Subscription sub) {
            String streamName = verifiedStreamNameFromParams(sub);
            if (streamName != null && !RoomMessagesChannel.guardedStream(streamName)) sub.streamFrom(streamName);
            else sub.reject();
        }
    }

    // -- RoomMessagesChannel ----------------------------------------------------------------------------

    /// A room's message stream (`<room gid param>:messages`), served only to a current member of
    /// the room the verified stream name points at (reference/app/channels/room_messages_channel.rb).
    static final class RoomMessagesChannel implements Channel {
        static final String STREAM_SUFFIX = "messages";

        private static final Set<String> ROOM_TYPES = Set.of(Room.OPEN, Room.CLOSED, Room.DIRECT);

        /// `RoomMessagesChannel.guarded_stream?`: true for the names this channel guards, whoever
        /// is asking (`stream_name.to_s.split(":", 2).second == "messages"`).
        static boolean guardedStream(String streamName) {
            int colon = streamName.indexOf(':');
            return colon >= 0 && streamName.substring(colon + 1).equals(STREAM_SUFFIX);
        }

        /// `RoomMessagesChannel.subscribable_room(user, stream_name)`.
        static Room subscribableRoom(campfire.db.Conn conn, long userId, String streamName) {
            if (!guardedStream(streamName)) return null;
            Room room = roomFrom(conn, streamName.substring(0, streamName.indexOf(':')));
            return room == null ? null : Room.findForUser(conn, userId, room.id());
        }

        /// `GlobalID::Locator.locate gid_param, only: Room`: `Room` finds any room, an STI subclass
        /// only rooms of its type.
        private static Room roomFrom(campfire.db.Conn conn, String gidParam) {
            // `GlobalID.parse` takes the URI form or its Base64 param.
            GlobalId gid = GlobalId.parse(gidParam);
            if (gid == null) gid = GlobalId.fromParam(gidParam);
            if (gid == null) return null;
            String model = gid.modelName();
            boolean anyRoom = model.equals("Room");
            if (!anyRoom && !ROOM_TYPES.contains(model)) return null;
            Long id = gid.longId();
            if (id == null) return null;
            Room room = Room.find(conn, id);
            return room != null && (anyRoom || model.equals(room.type())) ? room : null;
        }

        @Override
        public void subscribed(Subscription sub) {
            String streamName = verifiedStreamNameFromParams(sub);
            long userId = sub.currentUser().id();
            boolean member = streamName != null && sub.cable().db().read(conn -> subscribableRoom(conn, userId, streamName)) != null;
            if (member) sub.streamFrom(streamName);
            else sub.reject();
        }
    }
}
