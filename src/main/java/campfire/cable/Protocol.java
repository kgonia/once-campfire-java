package campfire.cable;

import io.vertx.core.json.Json;
import java.util.List;

/// The Action Cable wire format (`ActionCable::INTERNAL` in actioncable/lib/action_cable.rb).
///
/// Frames are Ruby hashes run through `ActiveSupport::JSON.encode`, so key order is the order the
/// hash literals are written in `Connection::Base` and `Channel::Base`.
final class Protocol {
    private Protocol() {}

    /// Offered in `Sec-WebSocket-Protocol`; the first one the client lists that's in here wins
    /// (websocket-driver walks the client's list, as Netty's handshaker does).
    static final List<String> PROTOCOLS = List.of("actioncable-v1-json", "actioncable-unsupported");

    /// `ActionCable::Server::Connections::BEAT_INTERVAL`, in seconds.
    static final int BEAT_INTERVAL = 3;

    /// `ActionCable::INTERNAL[:disconnect_reasons]`.
    static final String UNAUTHORIZED = "unauthorized", SERVER_RESTART = "server_restart",
        REMOTE = "remote";

    static String welcome() {
        return "{\"type\":\"welcome\"}";
    }

    /// `Connection::Base#beat`.
    static String ping(long unixSeconds) {
        return "{\"type\":\"ping\",\"message\":" + unixSeconds + "}";
    }

    /// `Connection::Base#close`: `reason` is nil when Rails closes without one.
    static String disconnect(String reason, boolean reconnect) {
        return "{\"type\":\"disconnect\",\"reason\":" + (reason == null ? "null" : Json.encode(reason)) + ",\"reconnect\":" + reconnect + "}";
    }

    static String confirmation(String identifier) {
        return "{\"identifier\":" + Json.encode(identifier) + ",\"type\":\"confirm_subscription\"}";
    }

    static String rejection(String identifier) {
        return "{\"identifier\":" + Json.encode(identifier) + ",\"type\":\"reject_subscription\"}";
    }

    /// `{"identifier":...,"message":...}` from the already-encoded identifier and message. Rails
    /// decodes each broadcast and re-encodes it inside this hash; passing the encoded payload
    /// through is the same bytes without the round trip per subscriber.
    static String message(String encodedIdentifier, String encodedMessage) {
        return "{\"identifier\":" + encodedIdentifier + ",\"message\":" + encodedMessage + "}";
    }
}
