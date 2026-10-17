package campfire.cable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import campfire.db.Database;
import campfire.db.TestDatabase;
import campfire.db.Times;
import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.sessions.Session;
import campfire.rails.HttpFormats;
import campfire.rails.Json;
import campfire.rails.Secrets;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.ext.web.Router;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/// A cable server with Campfire's channels over a small fixtures database, served by a plain
/// Vert.x HTTP server, and a WebSocket client for driving it.
@SuppressWarnings("try")
final class CableTestSupport implements AutoCloseable {
    static final String SECRET_KEY_BASE = "channels-test-secret-key-base";
    static final String WELCOME = "{\"type\":\"welcome\"}";
    static final String UNAUTHORIZED = "{\"type\":\"disconnect\",\"reason\":\"unauthorized\",\"reconnect\":false}";
    static final String CLOSE = "CLOSE:";
    static final String END = "END";

    final Path dir;
    final Database db;
    final Secrets secrets = new Secrets(SECRET_KEY_BASE);
    final Cable cable;
    final Broadcasts broadcasts;
    final Vertx vertx = Vertx.vertx();
    final HttpServer server;
    final WebSocketClient client;
    final int port;
    final String origin;
    final List<Client> clients = new ArrayList<>();

    // Users and rooms, as in the reference fixtures the Rust tests use.
    long david, kevin, jz, jason, bender;
    Room designers, watercooler, hq, benderAndKevin;

    static CableTestSupport start() throws Exception {
        return start(false);
    }

    static CableTestSupport start(boolean assumeSsl) throws Exception {
        return new CableTestSupport(assumeSsl);
    }

    private CableTestSupport(boolean assumeSsl) throws Exception {
        dir = Files.createTempDirectory("cable-test");
        db = TestDatabase.open(dir.resolve("test.sqlite3"));
        loadFixtures();
        cable = new Cable(db, secrets, token -> db.read(conn -> Session.findByToken(conn, token)), assumeSsl);
        broadcasts = new Broadcasts(cable);
        Router router = Router.router(vertx);
        router.route("/cable").handler(cable::upgrade);
        server = vertx.createHttpServer(Cable.configure(new HttpServerOptions())).requestHandler(router).listen(0, "127.0.0.1")
            .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        port = server.actualPort();
        origin = "http://127.0.0.1:" + port;
        client = vertx.createWebSocketClient();
    }

    private void loadFixtures() {
        db.write(tx -> {
            Instant now = Times.now();
            david = user(tx, "David", now);
            kevin = user(tx, "Kevin", now);
            jz = user(tx, "JZ", now);
            jason = user(tx, "Jason", now);
            bender = user(tx, "Bender", now);
            designers = room(tx, "Designers", Room.CLOSED, now, david, kevin, jz, jason);
            watercooler = room(tx, "Watercooler", Room.OPEN, now, david, jason);
            hq = room(tx, "HQ", Room.CLOSED, now, david);
            benderAndKevin = room(tx, null, Room.DIRECT, now, bender, kevin);
            return null;
        });
    }

    private static long user(Database.Tx tx, String name, Instant now) {
        return tx.insert("INSERT INTO users (name, email_address, created_at, updated_at) VALUES (?, ?, ?, ?)", name,
            name.toLowerCase() + "@example.com", now, now);
    }

    private static Room room(Database.Tx tx, String name, String type, Instant now, long... members) {
        long id = tx.insert("INSERT INTO rooms (name, type, creator_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)", name, type, members[0], now, now);
        for (long member : members) {
            tx.insert("INSERT INTO memberships (room_id, user_id, involvement, created_at, updated_at) VALUES (?, ?, 'mentions', ?, ?)", id, member, now, now);
        }
        return Room.find(tx, id);
    }

    Membership membership(Room room, long userId) {
        return db.read(conn -> Membership.find(conn, room.id(), userId));
    }

    String roomGid(Room room) {
        return Broadcasts.roomGid(room).toParam();
    }

    // -- Cookies and identifiers --------------------------------------------------------------------

    /// A session cookie for the user (`cookies.signed[:session_token]`).
    String cookieFor(long userId) {
        Session session = db.write(tx -> Session.start(tx, userId, "test", "8.8.8.8"));
        return cookieWithToken(session.token(), null);
    }

    String cookieWithToken(String token, Instant expiresAt) {
        return "session_token=" + HttpFormats.escape(secrets.signCookie("session_token", token, expiresAt));
    }

    String signedStreamName(String... streamables) {
        return secrets.signedStreamName(streamables);
    }

    static String identifier(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return Json.generate(map);
    }

    static String roomIdentifier(String channel, long roomId) {
        return identifier("channel", channel, "room_id", roomId);
    }

    static String confirmation(String identifier) {
        return "{\"identifier\":" + Json.encode(identifier) + ",\"type\":\"confirm_subscription\"}";
    }

    static String rejection(String identifier) {
        return "{\"identifier\":" + Json.encode(identifier) + ",\"type\":\"reject_subscription\"}";
    }

    /// The frame a broadcast of `encodedMessage` (already Active Support JSON) arrives in.
    static String delivery(String identifier, String encodedMessage) {
        return "{\"identifier\":" + Json.encode(identifier) + ",\"message\":" + encodedMessage + "}";
    }

    /// A JSON string literal the way Active Support encodes HTML in it.
    static String htmlJson(String html) {
        return Json.encode(html);
    }

    // -- Connecting ---------------------------------------------------------------------------------------

    /// Connects as the user and reads the welcome.
    Client connect(long userId) throws Exception {
        Client c = connectWithCookie(cookieFor(userId));
        assertEquals(WELCOME, c.next());
        return c;
    }

    Client connectWithCookie(String cookie) throws Exception {
        return connectWith(cookie, origin, List.of("actioncable-v1-json", "actioncable-unsupported"));
    }

    Client connectWith(String cookie, String origin, List<String> protocols) throws Exception {
        var options = new WebSocketConnectOptions().setHost("127.0.0.1").setPort(port).setURI("/cable").setSubProtocols(protocols);
        if (origin != null) options.addHeader("Origin", origin);
        if (cookie != null) options.addHeader("Cookie", cookie);
        var frames = new LinkedBlockingQueue<String>();
        WebSocket ws = client.connect(options).onSuccess(socket -> {
            socket.textMessageHandler(frames::add);
            socket.closeHandler(v -> {
                frames.add(CLOSE + socket.closeStatusCode());
                frames.add(END);
            });
        }).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        var c = new Client(ws, frames);
        clients.add(c);
        return c;
    }

    @Override
    public void close() throws Exception {
        Times.freeze(null);
        cable.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        db.close();
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /// Waits until `check` holds, for state the server changes without a reply to wait for.
    static void eventually(BooleanSupplier check) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            if (check.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("condition never held");
    }

    static final class Client {
        final WebSocket ws;
        final BlockingQueue<String> frames;

        Client(WebSocket ws, BlockingQueue<String> frames) {
            this.ws = ws;
            this.frames = frames;
        }

        void send(Map<String, Object> command) {
            sendRaw(Json.generate(command));
        }

        void sendRaw(String text) {
            ws.writeTextMessage(text);
        }

        private static Map<String, Object> command(String command, String identifier) {
            var map = new LinkedHashMap<String, Object>();
            map.put("command", command);
            map.put("identifier", identifier);
            return map;
        }

        void subscribe(String identifier) {
            send(command("subscribe", identifier));
        }

        void unsubscribe(String identifier) {
            send(command("unsubscribe", identifier));
        }

        /// `data` is the JSON the client's `perform` encodes.
        void perform(String identifier, String data) {
            var map = command("message", identifier);
            map.put("data", data);
            send(map);
        }

        void confirm(String identifier) throws InterruptedException {
            subscribe(identifier);
            assertEquals(confirmation(identifier), next(), "subscribing to " + identifier);
        }

        void reject(String identifier) throws InterruptedException {
            subscribe(identifier);
            assertEquals(rejection(identifier), next(), "subscribing to " + identifier);
        }

        /// The next frame within 5s, skipping pings: a text, `CLOSE:<code>` or `END`.
        String next() throws InterruptedException {
            return next(true);
        }

        String next(boolean skipPings) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                String frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (frame == null) throw new AssertionError("no frame within 5s");
                if (skipPings && frame.startsWith("{\"type\":\"ping\"")) continue;
                return frame;
            }
        }

        /// Asserts nothing arrives (pings aside) for a moment.
        void assertSilent() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
            while (true) {
                String frame = frames.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (frame == null) return;
                if (!frame.startsWith("{\"type\":\"ping\"")) assertNull(frame, "expected no frame");
            }
        }

        /// Reads until the server closes the socket, returning the text frames before the close.
        List<String> untilClosed() throws InterruptedException {
            var texts = new ArrayList<String>();
            while (true) {
                String frame = next();
                if (frame.equals(END)) return texts;
                if (!frame.startsWith(CLOSE)) texts.add(frame);
            }
        }
    }
}
