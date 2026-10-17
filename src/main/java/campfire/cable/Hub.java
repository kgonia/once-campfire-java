package campfire.cable;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// The in-process replacement for the Redis subscription adapter: the listeners of each
/// broadcasting, added on `stream_from` and dropped with the last one.
///
/// Payloads are already-encoded JSON, as on the Redis wire. Each broadcast builds the
/// `{"identifier":…,"message":…}` frame once per distinct channel identifier and hands it to every
/// listener's connection, which writes it in order with the rest of its frames.
final class Hub {
    /// One stream of one subscription, or a connection's internal channel (`encodedIdentifier` null,
    /// payloads handed over raw).
    static final class Listener {
        final String stream;
        final String encodedIdentifier;
        final Connection connection;
        volatile boolean active = true;

        Listener(String stream, String encodedIdentifier, Connection connection) {
            this.stream = stream;
            this.encodedIdentifier = encodedIdentifier;
            this.connection = connection;
        }

        boolean internal() {
            return encodedIdentifier == null;
        }
    }

    private final ConcurrentHashMap<String, Set<Listener>> streams = new ConcurrentHashMap<>();

    Listener subscribe(String stream, String encodedIdentifier, Connection connection) {
        var listener = new Listener(stream, encodedIdentifier, connection);
        streams.compute(stream, (name, listeners) -> {
            if (listeners == null) listeners = ConcurrentHashMap.newKeySet();
            listeners.add(listener);
            return listeners;
        });
        return listener;
    }

    void unsubscribe(Listener listener) {
        listener.active = false;
        streams.computeIfPresent(listener.stream, (name, listeners) -> {
            listeners.remove(listener);
            return listeners.isEmpty() ? null : listeners;
        });
    }

    /// Publishes `payload` to every current listener of `stream`; returns how many got it.
    int broadcast(String stream, String payload) {
        Set<Listener> listeners = streams.get(stream);
        if (listeners == null) return 0;
        HashMap<String, String> frames = null;
        int receivers = 0;
        for (Listener listener : listeners) {
            if (!listener.active) continue;
            String frame;
            if (listener.internal()) {
                frame = payload;
            } else {
                if (frames == null) frames = new HashMap<>(4);
                frame = frames.computeIfAbsent(listener.encodedIdentifier, id -> Protocol.message(id, payload));
            }
            listener.connection.deliver(listener, frame);
            receivers++;
        }
        return receivers;
    }

    /// Broadcastings with at least one listener (connections' internal channels included).
    int streamCount() {
        return streams.size();
    }
}
