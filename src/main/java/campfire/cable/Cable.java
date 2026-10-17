package campfire.cable;

import campfire.App;
import campfire.db.Database;
import campfire.db.Times;
import campfire.sessions.Session;
import campfire.rails.HttpFormats;
import campfire.rails.Json;
import campfire.rails.Secrets;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /// `config.action_cable.*` as the production reference runs it.
    ///
    /// @param allowedRequestOrigins exact `Origin` values accepted besides the same-origin rule
    /// @param assumeSsl `config.assume_ssl` (on unless `DISABLE_SSL`): the same-origin check then
    ///     compares against `https://<host>`
    public record Options(boolean disableRequestForgeryProtection, List<String> allowedRequestOrigins, boolean allowSameOriginAsHost,
                          boolean assumeSsl) {
        public static Options forConfig(campfire.Config config) {
            return new Options(false, List.of(), true, !config.disableSsl());
        }
    }

    private final Database db;
    private final Secrets secrets;
    private final Function<String, Session.WithUser> sessions;
    private final Options options;
    private final Hub hub = new Hub();
    private final Map<String, Supplier<Channel>> channels = new LinkedHashMap<>();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Object heartbeatLock = new Object();
    private ScheduledExecutorService heartbeat;

    public Cable(App app) {
        this(app.db(), app.secrets(), app.sessions()::find, Options.forConfig(app.config()));
    }

    /// @param sessions `Session.find_by(token:)` with its user, or null
    public Cable(Database db, Secrets secrets, Function<String, Session.WithUser> sessions, Options options) {
        this.db = db;
        this.secrets = secrets;
        this.sessions = sessions;
        this.options = options;
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

    /// `ActionCable::Server::Base#call` for `/cable`. Anything that isn't a WebSocket upgrade from an
    /// allowed origin gets Rails' 404 "Page not found"; an upgrade is accepted and then
    /// authenticated, as Rails does in `handle_open`.
    public void upgrade(RoutingContext rc) {
        HttpServerRequest request = rc.request();
        startHeartbeat();
        if (!websocketRequest(request) || !allowRequestOrigin(request)) {
            pageNotFound(rc);
            return;
        }
        String cookies = String.join("; ", request.headers().getAll("cookie"));
        request.toWebSocket().onSuccess(ws -> new Connection(this, ws, cookies).start()).onFailure(e -> {
            LOG.log(Level.FINE, "cable handshake failed", e);
            if (!rc.response().headWritten()) pageNotFound(rc);
        });
    }

    /// `WebSocket::Driver.websocket?(env)` plus what the handshake needs (version 13 and a key).
    private static boolean websocketRequest(HttpServerRequest request) {
        MultiMap headers = request.headers();
        boolean connectionUpgrade = headers.getAll("connection").stream()
            .anyMatch(value -> List.of(value.split(",")).stream().anyMatch(token -> token.strip().equalsIgnoreCase("upgrade")));
        String upgrade = headers.get("upgrade");
        return request.method() == HttpMethod.GET && connectionUpgrade && upgrade != null && upgrade.equalsIgnoreCase("websocket")
            && "13".equals(headers.get("sec-websocket-version")) && headers.get("sec-websocket-key") != null;
    }

    /// `Connection::Base#allow_request_origin?`.
    private boolean allowRequestOrigin(HttpServerRequest request) {
        if (options.disableRequestForgeryProtection()) return true;
        String origin = request.getHeader("origin");
        String host = request.getHeader("host");
        String proto = options.assumeSsl() || request.isSSL() || forwardedSsl(request.headers()) ? "https" : "http";
        if (options.allowSameOriginAsHost() && origin != null && origin.equals(proto + "://" + (host == null ? "" : host))) return true;
        if (origin != null && options.allowedRequestOrigins().contains(origin)) return true;
        LOG.severe("Request origin not allowed: " + origin);
        return false;
    }

    /// `Rack::Request#ssl?` for a request that didn't arrive over TLS itself.
    private static boolean forwardedSsl(MultiMap headers) {
        return "on".equals(first(headers, "x-forwarded-ssl")) || "https".equals(first(headers, "x-forwarded-scheme"))
            || "https".equals(first(headers, "x-forwarded-proto"));
    }

    private static String first(MultiMap headers, String name) {
        String value = headers.get(name);
        return value == null ? null : value.split(",", -1)[0].strip().toLowerCase(Locale.ROOT);
    }

    /// `Connection::Base#respond_to_invalid_request`.
    private static void pageNotFound(RoutingContext rc) {
        rc.response().setStatusCode(404).putHeader("Content-Type", "text/plain; charset=utf-8").end("Page not found");
    }

    // -- ApplicationCable::Connection ---------------------------------------------------------------

    /// `connect`: `current_user` from the signed `session_token` cookie
    /// (`Authentication::SessionLookup`), or null for `reject_unauthorized_connection`.
    Subscription.User authenticate(String cookieHeader) {
        try {
            String token = secrets.verifySignedCookie("session_token", cookie(cookieHeader, "session_token"), Times.now());
            if (token == null) return null;
            Session.WithUser found = sessions.apply(token);
            return found == null || found.user() == null ? null : new Subscription.User(found.user().id(), found.user().name());
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not look up the cable session", e);
            return null;
        }
    }

    /// `Rack::Utils.parse_cookies_header`: split on `/; */`, values unescaped, the first wins.
    static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split("; *")) {
            int eq = part.indexOf('=');
            if ((eq < 0 ? part : part.substring(0, eq)).equals(name)) return eq < 0 ? "" : HttpFormats.unescape(part.substring(eq + 1));
        }
        return null;
    }

    /// `ActionCable::Connection::InternalChannel#internal_channel` for `identified_by
    /// :current_user` (the user's GID param).
    static String internalChannel(long userId) {
        return "action_cable/" + Broadcasts.userGid(userId).toParam();
    }

    void opened(Connection connection) {
        connections.add(connection);
    }

    void closed(Connection connection) {
        connections.remove(connection);
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

    /// `SomeChannel.broadcast_to(broadcastables, message)`.
    public int broadcastTo(String channelClass, String json, String... broadcastables) {
        return deliver(Naming.broadcastingFor(channelClass, broadcastables), json);
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
        var message = new LinkedHashMap<String, Object>();
        message.put("type", "disconnect");
        message.put("reconnect", reconnect);
        return deliver(internalChannel(userId), Json.encode(message));
    }

    /// Broadcastings that currently have subscribers (connections' internal channels included).
    public int streamCount() {
        return hub.streamCount();
    }

    /// Open connections (authenticated and welcomed).
    public int connectionCount() {
        return connections.size();
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
