package campfire.cable;

import campfire.rails.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// A subscription's state as its channel sees it: params, the connection's user, streams,
/// rejection and transmissions. Only the connection's own thread touches it.
final class Subscription {
    /// `identified_by :current_user`, as loaded when the connection opened.
    record User(long id, String name) {}

    private final Cable cable;
    private final Connection connection;
    /// The registered class name, however the client spelled it ("::RoomChannel" is "RoomChannel").
    private final String className;
    private final String identifier;
    private final String encodedIdentifier;
    private final Map<String, Object> params;
    private final User user;
    private final List<Hub.Listener> streams = new ArrayList<>();
    private boolean rejected;
    boolean unsubscribed;

    Subscription(Cable cable, Connection connection, String className, String identifier, Map<String, Object> params, User user) {
        this.cable = cable;
        this.connection = connection;
        this.className = className;
        this.identifier = identifier;
        this.encodedIdentifier = Json.encode(identifier);
        this.params = params;
        this.user = user;
    }

    Cable cable() {
        return cable;
    }

    String className() {
        return className;
    }

    /// The raw identifier string the client subscribed with.
    String identifier() {
        return identifier;
    }

    Object param(String key) {
        return params.get(key);
    }

    User currentUser() {
        return user;
    }

    String broadcastingFor(String... broadcastables) {
        return Naming.broadcastingFor(className, broadcastables);
    }

    /// `broadcast_to` for this channel's class.
    void broadcastTo(Object message, String... broadcastables) {
        cable.deliver(broadcastingFor(broadcastables), Json.encode(message));
    }

    void streamFrom(String broadcasting) {
        if (unsubscribed) return;
        streams.add(cable.hub().subscribe(broadcasting, encodedIdentifier, connection));
    }

    void streamFor(String... broadcastables) {
        streamFrom(broadcastingFor(broadcastables));
    }

    void stopAllStreams() {
        for (Hub.Listener listener : streams) cable.hub().unsubscribe(listener);
        streams.clear();
    }

    void reject() {
        rejected = true;
    }

    /// `subscription_rejected?`
    boolean rejected() {
        return rejected;
    }

    /// Sends `{"identifier":...,"message":...}` to this subscriber only.
    void transmit(Object message) {
        connection.write(Protocol.message(encodedIdentifier, Json.encode(message)));
    }
}
