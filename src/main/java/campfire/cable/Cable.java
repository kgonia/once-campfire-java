package campfire.cable;

import campfire.App;
import campfire.db.Database;
import campfire.db.Times;
import campfire.sessions.Session;
import campfire.rails.HttpFormats;
import campfire.rails.Secrets;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/// `ActionCable.server`: the `/cable` endpoint speaking `actioncable-v1-json`, Campfire's
/// channels, the in-process pub/sub that replaces Redis, the heartbeat and remote disconnects.
///
/// Vert.x does the WebSocket handshake and framing; the subprotocols it may pick come from the
/// server options ({@link #configure}, applied to Quarkus' server by `CableServerOptions`). Each
/// connection runs on a virtual thread of its own (see {@link Connection}), so channel callbacks
/// may use the database; nothing here blocks the event loop.
public final class Cable implements Broadcasts.PubSub, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Cable.class.getName());

    /// The subprotocols the endpoint speaks, in Action Cable's order.
    public static final List<String> PROTOCOLS = Protocol.PROTOCOLS;

    private static final String SESSION_TOKEN = "session_token";

    private final Database db;
    private final Secrets secrets;
    private final Function<String, Session.WithUser> sessions;
    /// `config.assume_ssl` (on unless `DISABLE_SSL`): the same-origin check then compares against
    /// `https://<host>`.
    private final boolean assumeSsl;
    private final Hub hub = new Hub();
    private final Map<String, Supplier<Channel>> channels = new LinkedHashMap<>();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    /// Each user's connections from authentication on, which is when Rails subscribes a connection
    /// to its internal channel.
    private final ConcurrentHashMap<Long, Set<Connection>> byUser = new ConcurrentHashMap<>();
    private final Object heartbeatLock = new Object();
    private ScheduledExecutorService heartbeat;

    public Cable(App app) {
        this(app.db(), app.secrets(), app.sessions()::find, !app.config().disableSsl());
    }

    /// @param sessions `Session.find_by(token:)` with its user, or null
    public Cable(Database db, Secrets secrets, Function<String, Session.WithUser> sessions, boolean assumeSsl) {
        this.db = db;
        this.secrets = secrets;
        this.sessions = sessions;
        this.assumeSsl = assumeSsl;
        Channels.register(this);
    }

    /// Lets the HTTP server negotiate Action Cable's subprotocols (keeping any already set).
    public static HttpServerOptions configure(HttpServerOptions options) {
        var protocols = new ArrayList<String>();
        if (options.getWebSocketSubProtocols() != null) protocols.addAll(options.getWebSocketSubProtocols());
        for (String protocol : PROTOCOLS) if (!protocols.contains(protocol)) protocols.add(protocol);
        return options.setWebSocketSubProtocols(protocols);
    }

    Database db() {
        return db;
    }

    Secrets secrets() {
        return secrets;
    }

    Hub hub() {
        return hub;
    }

    void register(String className, Supplier<Channel> factory) {
        channels.put(className, factory);
    }

    Supplier<Channel> channel(String className) {
        return channels.get(className);
    }

    // -- The endpoint -------------------------------------------------------------------------------

    /// `ActionCable::Server::Base#call` for `/cable`. Anything that isn't a WebSocket upgrade from
    /// the same origin gets Rails' 404 "Page not found"; an upgrade is accepted and then
    /// authenticated, as Rails does in `handle_open`. Vert.x answers an upgrade it can't
    /// complete (wrong method, `Connection` or version, no key) with its own 400.
    public void upgrade(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        startHeartbeat();
        if (!"websocket".equalsIgnoreCase(request.getHeader("upgrade")) || !sameOrigin(request)) {
            pageNotFound(rc);
            return;
        }
        Cookie cookie = request.getCookie(SESSION_TOKEN);
        String sessionToken = cookie == null ? null : HttpFormats.unescape(cookie.getValue());
        request.toWebSocket().onSuccess(ws -> new Connection(this, ws, sessionToken).start()).onFailure(e -> {
            LOG.log(Level.FINE, "cable handshake failed", e);
            if (!rc.response().headWritten()) pageNotFound(rc);
        });
    }

    /// `Connection::Base#allow_request_origin?` as Campfire configures it: same origin only. Vert.x
    /// has already applied a trusted proxy's forwarded scheme and host (`quarkus.http.proxy.*`).
    private boolean sameOrigin(HttpServerRequest request) {
        String origin = request.getHeader("origin");
        String host = campfire.web.Front.authority(request);
        String scheme = assumeSsl || request.isSSL() ? "https://" : "http://";
        if (origin != null && origin.equals(scheme + (host == null ? "" : host))) return true;
        LOG.severe("Request origin not allowed: " + origin);
        return false;
    }

    /// `Connection::Base#respond_to_invalid_request`.
    private static void pageNotFound(RoutingContext rc) {
        rc.response().setStatusCode(404).putHeader("Content-Type", "text/plain; charset=utf-8").end("Page not found");
    }

    // -- ApplicationCable::Connection ---------------------------------------------------------------

    /// `connect`: `current_user` from the signed `session_token` cookie
    /// (`Authentication::SessionLookup`), or null for `reject_unauthorized_connection`.
    Subscription.User authenticate(String signedToken) {
        if (signedToken == null) return null;
        try {
            String token = secrets.verifySignedCookie(SESSION_TOKEN, signedToken, Times.now());
            if (token == null) return null;
            Session.WithUser found = sessions.apply(token);
            return found == null || found.user() == null ? null : new Subscription.User(found.user().id(), found.user().name());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not look up the cable session", e);
            return null;
        }
    }

    void identified(Connection connection, long userId) {
        byUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(connection);
    }

    void opened(Connection connection) {
        connections.add(connection);
    }

    void closed(Connection connection, Subscription.User user) {
        connections.remove(connection);
        if (user != null) {
            byUser.computeIfPresent(user.id(), (id, listening) -> {
                listening.remove(connection);
                return listening.isEmpty() ? null : listening;
            });
        }
    }

    // -- Broadcasting ---------------------------------------------------------------------------------

    /// `ActionCable.server.broadcast(stream, message)` with `json` already encoded.
    @Override
    public void broadcast(String stream, String json) {
        deliver(stream, json);
    }

    /// {@link #broadcast}, returning how many subscriptions (in this process) received it.
    public int deliver(String stream, String json) {
        if (LOG.isLoggable(Level.FINE)) LOG.fine("[ActionCable] Broadcasting to " + stream + ": " + json);
        return hub.broadcast(stream, json);
    }

    /// `User#reset_remote_connections`: every connection of the user is sent
    /// `{"type":"disconnect","reason":"remote","reconnect":true}` and closed.
    public int disconnectUser(long userId) {
        return disconnectUser(userId, true);
    }

    /// `ActionCable.server.remote_connections.where(current_user: user).disconnect(reconnect:)`
    /// (`User#close_remote_connections`; deactivating and banning pass false). Returns how many
    /// connections were listening.
    public int disconnectUser(long userId, boolean reconnect) {
        Set<Connection> listening = byUser.get(userId);
        if (listening == null) return 0;
        int count = 0;
        for (Connection connection : listening) {
            connection.disconnect(reconnect);
            count++;
        }
        return count;
    }

    /// Broadcastings that currently have subscribers.
    public int streamCount() {
        return hub.streamCount();
    }

    /// `ActionCable.server.restart`: closes every connection with `server_restart`.
    public void restart() {
        for (Connection connection : connections) connection.restart();
    }

    /// Restarts every connection and stops the heartbeat.
    @Override
    public void close() {
        restart();
        synchronized (heartbeatLock) {
            if (heartbeat != null) heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    // -- Heartbeat --------------------------------------------------------------------------------------

    /// The server-wide heartbeat, started on the first request like Rails' `setup_heartbeat_timer`,
    /// so every connection pings in step with one frame.
    private void startHeartbeat() {
        synchronized (heartbeatLock) {
            if (heartbeat != null) return;
            heartbeat = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("cable-heartbeat").daemon().factory());
            heartbeat.scheduleAtFixedRate(this::beat, Protocol.BEAT_INTERVAL, Protocol.BEAT_INTERVAL, TimeUnit.SECONDS);
        }
    }

    private void beat() {
        try {
            String ping = Protocol.ping(System.currentTimeMillis() / 1000);
            for (Connection connection : connections) connection.beat(ping);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "cable heartbeat failed", e);
        }
    }
}
