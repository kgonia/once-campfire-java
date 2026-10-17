package campfire.cable;

import campfire.rails.Json;
import io.vertx.core.http.ServerWebSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/// `ActionCable::Connection::Base` and `Connection::Subscriptions`: one virtual thread per socket.
///
/// Everything that happens to a connection (client commands, deliveries from its streams, remote
/// disconnects, the socket closing) is an event on one queue, handled in order on the
/// connection's thread, where the channels' callbacks run and may block on the database. So a
/// subscription's confirmation always goes out before anything its new streams deliver, and the
/// commands that arrive while the connection is authenticating wait their turn (Rails'
/// `MessageBuffer`). Pings skip the queue: they're written straight to the socket.
final class Connection {
    private static final Logger LOG = Logger.getLogger(Connection.class.getName());

    /// The most subscriptions one connection may hold, and the longest identifier it may subscribe
    /// with (Rails has no bounds; a page subscribes to six channels with identifiers of a few
    /// hundred bytes).
    static final int MAX_SUBSCRIPTIONS = 64;
    static final int MAX_IDENTIFIER_BYTES = 4096;
    /// Client messages queued before the socket is paused, so a client sending commands faster than
    /// they're handled is held back by TCP.
    private static final int INCOMING_CAPACITY = 16;
    /// Deliveries queued or being written before the client counts as lagging and is disconnected
    /// with `reconnect: true`, rather than being buffered for without bound.
    static final int MAX_BACKLOG = 256;
    /// How long to wait for the closing handshake after we close.
    private static final long CLOSE_TIMEOUT_SECONDS = 15;

    private sealed interface Event permits Text, Delivery, Close, Closed {}

    private record Text(String text) implements Event {}

    private record Delivery(Hub.Listener listener, String frame) implements Event {}

    /// We decided to close: `reason` may be null (a lagging stream), `reconnect` any JSON value.
    private record Close(String reason, Object reconnect) implements Event {}

    private enum Closed implements Event { INSTANCE }

    private record Entry(Channel channel, Subscription sub) {}

    private final Cable cable;
    private final ServerWebSocket ws;
    private final String cookies;
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final AtomicInteger incoming = new AtomicInteger();
    private final AtomicInteger backlog = new AtomicInteger();
    private final AtomicBoolean lagged = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private volatile boolean open;
    private volatile boolean closing;
    private Subscription.User user;
    private Hub.Listener internal;
    /// Keyed by the raw identifier string, in subscription order (a Ruby hash).
    private final LinkedHashMap<String, Entry> subscriptions = new LinkedHashMap<>();

    Connection(Cable cable, ServerWebSocket ws, String cookies) {
        this.cable = cable;
        this.ws = ws;
        this.cookies = cookies;
    }

    /// Called on the event loop as soon as the handshake is done, before any message can arrive.
    void start() {
        ws.textMessageHandler(text -> {
            if (incoming.incrementAndGet() >= INCOMING_CAPACITY && paused.compareAndSet(false, true)) ws.pause();
            events.add(new Text(text));
        });
        ws.binaryMessageHandler(buffer -> LOG.severe("Couldn't handle non-string message: Array"));
        ws.exceptionHandler(e -> LOG.log(Level.FINE, "cable socket error", e));
        ws.closeHandler(v -> events.add(Closed.INSTANCE));
        Thread.ofVirtual().name("cable-connection").start(this::run);
    }

    // -- The connection's thread -------------------------------------------------------------------

    private void run() {
        try {
            // handle_open: connect, subscribe to the internal channel, welcome, then process whatever
            // arrived meanwhile.
            user = cable.authenticate(cookies);
            if (user == null) {
                rejectUnauthorized();
                return;
            }
            internal = cable.hub().subscribe(Cable.internalChannel(user.id()), null, this);
            // A ban or sign-out that disconnected this user between the check above and that
            // subscription went unheard, so check again now that it would be heard (Rails has this gap).
            if (cable.authenticate(cookies) == null) {
                rejectUnauthorized();
                return;
            }
            write(Protocol.welcome());
            open = true;
            cable.opened(this);
            loop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "cable connection failed", e);
            ws.close((short) 1011);
        } finally {
            open = false;
            cable.closed(this);
            if (internal != null) cable.hub().unsubscribe(internal);
            handleClose();
        }
    }

    private void loop() throws InterruptedException {
        while (true) {
            Event event = closing ? events.poll(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) : events.take();
            switch (event) {
                case null -> {
                    return;
                }
                case Closed c -> {
                    return;
                }
                case Text(String text) -> {
                    if (incoming.decrementAndGet() <= INCOMING_CAPACITY / 2 && paused.compareAndSet(true, false)) ws.resume();
                    if (!closing) dispatch(text);
                }
                case Delivery(Hub.Listener listener, String frame) -> {
                    if (listener.internal()) processInternalMessage(frame);
                    else if (!closing && listener.active) ws.writeTextMessage(frame).onComplete(ar -> backlog.decrementAndGet());
                    else backlog.decrementAndGet();
                }
                case Close(String reason, Object reconnect) -> close(reason, reconnect);
            }
        }
    }

    /// `Connection::Base#close`: tell the client why, then close the socket (1000, no reason). The
    /// loop carries on until the closing handshake completes, ignoring everything but that.
    private void close(String reason, Object reconnect) {
        if (closing) return;
        closing = true;
        open = false;
        ws.writeTextMessage(Protocol.disconnect(reason, reconnect));
        ws.close((short) 1000);
    }

    /// `respond_to_invalid_request` for an unauthorized connection: don't reconnect, and close.
    private void rejectUnauthorized() {
        LOG.severe("An unauthorized connection attempt was rejected");
        close(Protocol.UNAUTHORIZED, false);
    }

    /// `InternalChannel#process_internal_message`.
    private void processInternalMessage(String payload) {
        if (Json.parseOrNull(payload) instanceof Map<?, ?> message && "disconnect".equals(message.get("type"))) {
            LOG.info("Removing connection (" + Cable.internalChannel(user.id()) + ")");
            close(Protocol.REMOTE, message.containsKey("reconnect") ? message.get("reconnect") : Boolean.TRUE);
        }
    }

    // -- From any thread --------------------------------------------------------------------------

    /// A frame from one of this connection's streams. A connection that has fallen too far behind
    /// is closed with `reconnect: true` (as Rails closes without a reason) instead.
    void deliver(Hub.Listener listener, String frame) {
        if (closing) return;
        if (!listener.internal() && backlog.get() >= MAX_BACKLOG) {
            if (lagged.compareAndSet(false, true)) events.add(new Close(null, Boolean.TRUE));
            return;
        }
        backlog.incrementAndGet();
        events.add(new Delivery(listener, frame));
    }

    /// Writes straight to the socket (Vert.x queues it on the event loop in call order).
    void write(String frame) {
        if (!closing) ws.writeTextMessage(frame);
    }

    /// The heartbeat, for connections past `welcome`.
    void beat(String ping) {
        if (open && !closing) ws.writeTextMessage(ping);
    }

    /// `ActionCable.server.restart`.
    void restart() {
        events.add(new Close(Protocol.SERVER_RESTART, Boolean.TRUE));
    }

    // -- Subscriptions ------------------------------------------------------------------------------

    /// `Subscriptions#execute_command`. Anything malformed raises in Rails, which is logged and
    /// otherwise ignored; the connection stays open.
    private void dispatch(String text) {
        if (!(Json.parseOrNull(text) instanceof Map<?, ?> data)) {
            LOG.severe("Could not execute command from (" + text + ")");
            return;
        }
        switch (data.get("command") instanceof String command ? command : "") {
            case "subscribe" -> add(data);
            case "unsubscribe" -> remove(data);
            case "message" -> performAction(data);
            default -> LOG.severe("Received unrecognized command in " + text);
        }
    }

    /// `Subscriptions#add`. A repeated identifier (byte for byte) is ignored without a reply.
    @SuppressWarnings("unchecked")
    private void add(Map<?, ?> data) {
        if (!(data.get("identifier") instanceof String identifier)) {
            LOG.severe("Could not execute command: missing identifier");
            return;
        }
        if (!(Json.parseOrNull(identifier) instanceof Map<?, ?> params)) {
            LOG.severe("Could not execute command: invalid identifier " + identifier);
            return;
        }
        if (subscriptions.containsKey(identifier)) return;
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS || identifier.length() > MAX_IDENTIFIER_BYTES) {
            LOG.severe("Could not execute command: subscription limit reached (" + subscriptions.size() + ")");
            return;
        }
        String requested = params.get("channel") instanceof String channel ? channel : "";
        String className = requested.startsWith("::") ? requested.substring(2) : requested;
        Supplier<Channel> factory = cable.channel(className);
        if (factory == null) {
            LOG.severe("Subscription class not found: " + Json.encode(requested));
            return;
        }
        var sub = new Subscription(cable, this, className, identifier, (Map<String, Object>) params, user);
        var entry = new Entry(factory.get(), sub);
        subscriptions.put(identifier, entry);
        subscribeToChannel(entry);
    }

    /// `Channel::Base#subscribe_to_channel`.
    private void subscribeToChannel(Entry entry) {
        Subscription sub = entry.sub();
        try {
            entry.channel().subscribed(sub);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Could not execute command from " + sub.identifier() + ": " + e.getMessage(), debug(e));
            return;
        }
        if (sub.rejected()) {
            removeSubscription(entry);
            write(Protocol.rejection(sub.identifier()));
        } else {
            write(Protocol.confirmation(sub.identifier()));
        }
    }

    /// `Subscriptions#remove`: no reply either way.
    private void remove(Map<?, ?> data) {
        Entry entry = find(data);
        if (entry == null) LOG.severe("Unable to find subscription with identifier: " + data.get("identifier"));
        else removeSubscription(entry);
    }

    /// `Subscriptions#remove_subscription` → `Channel::Base#unsubscribe_from_channel`.
    private void removeSubscription(Entry entry) {
        subscriptions.remove(entry.sub().identifier());
        Subscription sub = entry.sub();
        sub.unsubscribed = true;
        try {
            entry.channel().unsubscribed(sub);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Could not execute command from " + sub.identifier() + ": " + e.getMessage(), debug(e));
        }
        sub.stopAllStreams();
    }

    /// `Subscriptions#perform_action` → `Channel::Base#perform_action`.
    @SuppressWarnings("unchecked")
    private void performAction(Map<?, ?> data) {
        Entry entry = find(data);
        if (entry == null) {
            LOG.severe("Unable to find subscription with identifier: " + data.get("identifier"));
            return;
        }
        if (!(data.get("data") instanceof String encoded) || !(Json.parseOrNull(encoded) instanceof Map<?, ?> payload)) {
            LOG.severe("Could not execute command: invalid data");
            return;
        }
        String action;
        switch (payload.get("action")) {
            case null -> action = "receive";
            case String s -> action = s.isBlank() ? "receive" : s;
            default -> {
                LOG.severe("Could not execute command: invalid action");
                return;
            }
        }
        try {
            if (!entry.channel().perform(action, (Map<String, Object>) payload, entry.sub())) {
                LOG.severe("Unable to process " + entry.sub().className() + "#" + action);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Could not execute command from " + entry.sub().identifier() + ": " + e.getMessage(), debug(e));
        }
    }

    /// `Connection::Base#handle_close`: unsubscribe everything.
    private void handleClose() {
        for (Entry entry : new ArrayList<>(subscriptions.values())) removeSubscription(entry);
    }

    private Entry find(Map<?, ?> data) {
        return data.get("identifier") instanceof String identifier ? subscriptions.get(identifier) : null;
    }

    /// Ruby-ish failures are expected; anything else gets its stack trace.
    private static Throwable debug(Exception e) {
        return e instanceof Channel.Failure ? null : e;
    }
}
