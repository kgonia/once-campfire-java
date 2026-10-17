package campfire.cable;

import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.rails.GlobalId;
import campfire.rails.Json;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Campfire's channels (reference/app/channels) and `Turbo::StreamsChannel` with
/// `RoomStreamsAreAuthorized` prepended. Each matches its Ruby class: identifier, streams,
/// payloads and callback order (`on_subscribe` runs after `subscribed`, before the confirmation).
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
    static class RoomChannel implements Channel {
        Room room;

        /// `stream_for @room` if the user is a member, else `reject`.
        static Room subscribe(Subscription sub) {
            Room room = findRoom(sub);
            if (room != null) sub.streamFor(Broadcasts.roomGid(room).toParam());
            else sub.reject();
            return room;
        }

        /// `current_user.rooms.find_by(id: params[:room_id])`
        private static Room findRoom(Subscription sub) {
            Long roomId = castId(sub.param("room_id"));
            if (roomId == null) return null;
            long userId = sub.currentUser().id();
            return sub.cable().db().read(conn -> Room.findForUser(conn, userId, roomId));
        }

        @Override
        public void subscribed(Subscription sub) {
            room = subscribe(sub);
        }

        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (!action.equals("subscribed") || sub.rejected()) return false;
            subscribed(sub);
            return true;
        }

        /// `@room` is only nil when `subscribed` failed, and then Ruby raises NoMethodError.
        Room room(String method) {
            if (room == null) throw new Channel.Failure("undefined method '" + method + "' for nil");
            return room;
        }
    }

    /// How Active Record casts a value for an integer `id` condition: numbers are truncated,
    /// booleans are 1 and 0, and strings go through `to_i` unless they don't start like a number
    /// (`non_numeric_string?`), which matches nothing. So does anything out of range.
    static Long castId(Object value) {
        return switch (value) {
            case Long l -> l;
            case Integer i -> (long) i;
            case BigInteger b -> null;
            case Double d -> Double.isFinite(d) && Math.abs(d) < 9.2e18 ? (long) (double) d : null;
            case Boolean b -> b ? 1L : 0L;
            case String s -> integerCast(s);
            case null, default -> null;
        };
    }

    /// Ruby's whitespace for `to_i`: space, \t, \n, \v, \f, \r.
    private static boolean rubySpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    static Long integerCast(String s) {
        int i = 0;
        while (i < s.length() && rubySpace(s.charAt(i))) i++;
        if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
        if (i >= s.length() || s.charAt(i) < '0' || s.charAt(i) > '9') return null;
        return toIChecked(s);
    }

    /// `String#to_i` (an optional `0d`, underscores between digits), or null past a long.
    static Long toIChecked(String s) {
        int i = 0;
        while (i < s.length() && rubySpace(s.charAt(i))) i++;
        boolean negative = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) negative = s.charAt(i++) == '-';
        if (s.startsWith("0d", i) || s.startsWith("0D", i)) i += 2;
        BigInteger number = BigInteger.ZERO;
        boolean previousDigit = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                number = number.multiply(BigInteger.TEN).add(BigInteger.valueOf(c - '0'));
                previousDigit = true;
                if (number.bitLength() > 64) break;
            } else if (c == '_' && previousDigit) {
                previousDigit = false;
            } else {
                break;
            }
        }
        if (negative) number = number.negate();
        return number.bitLength() < 64 ? number.longValue() : null;
    }

    // -- PresenceChannel --------------------------------------------------------------------------------

    /// A `RoomChannel` that marks the membership connected while subscribed
    /// (`Membership::Connectable`) and tells the user's other windows the room has been read.
    static final class PresenceChannel extends RoomChannel {
        private interface Change {
            void apply(campfire.db.Database.Tx tx, long roomId, long userId);
        }

        /// `subscribed`, then `on_subscribe :present, unless: :subscription_rejected?`.
        @Override
        public void subscribed(Subscription sub) {
            room = subscribe(sub);
            if (!sub.rejected()) present(sub);
        }

        /// `on_unsubscribe :absent, unless: :subscription_rejected?`.
        @Override
        public void unsubscribed(Subscription sub) {
            if (!sub.rejected()) absent(sub);
        }

        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (sub.rejected()) return false;
            switch (action) {
                case "present" -> present(sub);
                case "absent" -> absent(sub);
                case "refresh" -> refresh(sub);
                case "subscribed" -> room = subscribe(sub);
                default -> {
                    return false;
                }
            }
            return true;
        }

        /// `membership.present`, then `broadcast_read_room`
        /// (`ActionCable.server.broadcast "user_#{id}_reads", { room_id: membership.room_id }`).
        private void present(Subscription sub) {
            change(sub, Membership::present);
            long userId = sub.currentUser().id();
            long roomId = membership(sub).roomId();
            sub.cable().deliver(ReadRoomsChannel.streamNameFor(userId), Json.encode(Map.of("room_id", roomId)));
        }

        /// `membership.disconnected`
        private void absent(Subscription sub) {
            change(sub, Membership::disconnected);
        }

        /// `membership.refresh_connection`
        private void refresh(Subscription sub) {
            change(sub, Membership::refreshConnection);
        }

        /// `@room.memberships.find_by(user: current_user)`, which is nil (and so raises
        /// NoMethodError) once the membership is gone.
        private Membership membership(Subscription sub) {
            long roomId = room("memberships").id(), userId = sub.currentUser().id();
            Membership membership = sub.cable().db().read(conn -> Membership.find(conn, roomId, userId));
            if (membership == null) throw nilMembership();
            return membership;
        }

        private void change(Subscription sub, Change change) {
            long roomId = room("memberships").id(), userId = sub.currentUser().id();
            boolean found = sub.cable().db().write(tx -> {
                if (Membership.find(tx, roomId, userId) == null) return false;
                change.apply(tx, roomId, userId);
                return true;
            });
            if (!found) throw nilMembership();
        }

        private static Channel.Failure nilMembership() {
            return new Channel.Failure("undefined method for nil (membership)");
        }
    }

    // -- TypingNotificationsChannel -------------------------------------------------------------------

    static final class TypingNotificationsChannel extends RoomChannel {
        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (sub.rejected()) return false;
            switch (action) {
                case "start", "stop" -> broadcast(action, sub);
                case "subscribed" -> room = subscribe(sub);
                default -> {
                    return false;
                }
            }
            return true;
        }

        /// `broadcast_to @room, action:, user: current_user.slice(:id, :name)`.
        private void broadcast(String action, Subscription sub) {
            Room room = room("to_gid_param");
            var user = new LinkedHashMap<String, Object>();
            user.put("id", sub.currentUser().id());
            user.put("name", sub.currentUser().name());
            var payload = new LinkedHashMap<String, Object>();
            payload.put("action", action);
            payload.put("user", user);
            sub.broadcastTo(payload, Broadcasts.roomGid(room).toParam());
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

        /// `subscribed` is public, so it's an action too.
        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (!action.equals("subscribed")) return false;
            subscribed(sub);
            return true;
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

        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (!action.equals("subscribed")) return false;
            subscribed(sub);
            return true;
        }
    }

    // -- Signed stream names --------------------------------------------------------------------------

    /// `verified_stream_name_from_params`: `params[:signed_stream_name]` verified with
    /// `Turbo.signed_stream_verifier`. A missing or null name is simply unverified; any other
    /// non-string makes `MessageVerifier#verified` raise.
    static String verifiedStreamNameFromParams(Subscription sub) {
        return switch (sub.param("signed_stream_name")) {
            case null -> null;
            case String signed -> sub.cable().secrets().verifiedStreamName(signed);
            case Object other -> throw new Channel.Failure("undefined method 'valid_encoding?' for " + Json.encode(other));
        };
    }

    /// `Turbo::StreamsChannel` with `RoomStreamsAreAuthorized` prepended: a room's message stream
    /// is turned away here even when validly signed, so `RoomMessagesChannel` is the only door.
    static final class StreamsChannel implements Channel {
        @Override
        public void subscribed(Subscription sub) {
            String streamName = verifiedStreamNameFromParams(sub);
            // RoomStreamsAreAuthorized sees a name that failed verification as nil.to_s.
            if (RoomMessagesChannel.guardedStream(streamName == null ? "" : streamName)) {
                sub.reject();
            } else if (streamName != null) {
                sub.streamFrom(streamName);
            } else {
                sub.reject();
            }
        }
    }

    // -- RoomMessagesChannel ----------------------------------------------------------------------------

    /// A room's message stream (`<room gid param>:messages`), served only to a current member of
    /// the room the verified stream name points at (reference/app/channels/room_messages_channel.rb).
    static final class RoomMessagesChannel implements Channel {
        static final String STREAM_SUFFIX = "messages";

        /// Constants a GID could name that aren't rooms (`only: Room` turns them away without raising).
        private static final Set<String> KNOWN_MODELS = Set.of("Account", "Ban", "Boost", "Current", "Membership", "Message",
            "Push::Subscription", "Search", "Session", "User", "Webhook");
        private static final Set<String> ROOM_TYPES = Set.of(Room.OPEN, Room.CLOSED, Room.DIRECT);

        /// `RoomMessagesChannel.guarded_stream?`: true for the names this channel guards, whoever
        /// is asking (`stream_name.to_s.split(":", 2).second == "messages"`).
        static boolean guardedStream(String streamName) {
            int colon = streamName.indexOf(':');
            return colon >= 0 && streamName.substring(colon + 1).equals(STREAM_SUFFIX);
        }

        /// `RoomMessagesChannel.subscribable_room(user, stream_name)`.
        static Room subscribableRoom(campfire.db.Conn conn, long userId, String streamName) {
            int colon = streamName.indexOf(':');
            if (colon < 0 || !streamName.substring(colon + 1).equals(STREAM_SUFFIX)) return null;
            Room room = roomFrom(conn, streamName.substring(0, colon));
            return room == null ? null : Room.findForUser(conn, userId, room.id());
        }

        /// `GlobalID::Locator.locate gid_param, only: Room`, with `RecordNotFound` as nil.
        private static Room roomFrom(campfire.db.Conn conn, String gidParam) {
            // `GlobalID.parse` takes the URI form or its Base64 param.
            GlobalId gid = GlobalId.parse(gidParam);
            if (gid == null) gid = GlobalId.fromParam(gidParam);
            if (gid == null) return null;
            // `find_allowed?(gid.model_class, only: Room)`: Room or one of its STI subclasses, whose
            // `find` also requires the type to match.
            String requiredType;
            if (gid.modelName().equals("Room")) {
                requiredType = null;
            } else if (ROOM_TYPES.contains(gid.modelName())) {
                requiredType = gid.modelName();
            } else if (KNOWN_MODELS.contains(gid.modelName())) {
                return null;
            } else {
                // `model_name.constantize` raises NameError.
                throw new Channel.Failure("uninitialized constant " + gid.modelName());
            }
            Long id = gid.longId();
            if (id == null) return null;
            Room room = Room.find(conn, id);
            return room != null && (requiredType == null || requiredType.equals(room.type())) ? room : null;
        }

        /// The verified stream name, if present and for a room the user belongs to.
        private static String authorizedStreamName(Subscription sub) {
            String streamName = verifiedStreamNameFromParams(sub);
            if (streamName == null || streamName.isBlank()) return null;
            long userId = sub.currentUser().id();
            Room room = sub.cable().db().read(conn -> subscribableRoom(conn, userId, streamName));
            return room == null ? null : streamName;
        }

        @Override
        public void subscribed(Subscription sub) {
            String streamName = authorizedStreamName(sub);
            if (streamName != null) sub.streamFrom(streamName);
            else sub.reject();
        }

        /// Its public methods: `subscribed`, and `verified_stream_name_from_params` from
        /// `include Turbo::Streams::StreamName::ClassMethods` (which returns without transmitting).
        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            if (sub.rejected()) return false;
            switch (action) {
                case "subscribed" -> subscribed(sub);
                case "verified_stream_name_from_params" -> verifiedStreamNameFromParams(sub);
                default -> {
                    return false;
                }
            }
            return true;
        }
    }
}
