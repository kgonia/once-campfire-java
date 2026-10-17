package campfire.cable;

import static campfire.cable.CableTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import campfire.rails.Json;
import io.vertx.core.http.UpgradeRejectedException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// The Action Cable protocol over a real socket (ports crates/cable/tests/protocol.rs and the
/// unit tests of protocol.rs, naming.rs, pubsub.rs and server.rs).
class CableProtocolTest {
    private CableTestSupport app;

    @AfterEach
    void stop() throws Exception {
        if (app != null) app.close();
    }

    // -- Framing and naming -------------------------------------------------------------------------

    @Test
    void framesAreActiveSupportJsonInRailsKeyOrder() {
        assertEquals("{\"type\":\"welcome\"}", Protocol.welcome());
        assertEquals("{\"type\":\"ping\",\"message\":1700000000}", Protocol.ping(1700000000));
        assertEquals("{\"type\":\"disconnect\",\"reason\":\"unauthorized\",\"reconnect\":false}", Protocol.disconnect(Protocol.UNAUTHORIZED, false));
        assertEquals("{\"type\":\"disconnect\",\"reason\":null,\"reconnect\":true}", Protocol.disconnect(null, true));
        String identifier = "{\"channel\":\"RoomChannel\",\"room_id\":1}";
        assertEquals("{\"identifier\":\"{\\\"channel\\\":\\\"RoomChannel\\\",\\\"room_id\\\":1}\",\"type\":\"confirm_subscription\"}",
            Protocol.confirmation(identifier));
        assertEquals("{\"identifier\":\"{\\\"channel\\\":\\\"RoomChannel\\\",\\\"room_id\\\":1}\",\"type\":\"reject_subscription\"}",
            Protocol.rejection(identifier));
        assertEquals("{\"identifier\":\"\\u003cx\\u003e\",\"type\":\"confirm_subscription\"}", Protocol.confirmation("<x>"));
        assertEquals("{\"identifier\":\"id\",\"message\":{\"a\":1}}", Protocol.message(Json.encode("id"), "{\"a\":1}"));
    }

    @Test
    void streamNames() {
        String room = campfire.rails.GlobalId.of("Rooms::Open", 1).toParam();
        assertEquals("Z2lkOi8vY2FtcGZpcmUvUm9vbXM6Ok9wZW4vMQ", room);
        assertEquals(room + ":messages", Broadcasts.streamName(room, "messages"));
    }

    @Test
    void roomIdsAreNumbersOrDecimalStrings() {
        assertEquals(5L, Channels.id(5L));
        assertEquals(5L, Channels.id("5"));
        assertEquals(-1L, Channels.id("-1"));
        assertNull(Channels.id(5.9));
        assertNull(Channels.id(true));
        assertNull(Channels.id(" 12abc"));
        assertNull(Channels.id(""));
        assertNull(Channels.id(null));
        assertNull(Channels.id("99999999999999999999"));
        assertNull(Channels.id(new java.math.BigInteger("99999999999999999999")));
    }

    @Test
    void guardsOnlyMessageStreams() {
        assertTrue(Channels.RoomMessagesChannel.guardedStream("Z2lkOi8vY2FtcGZpcmUvUm9vbXM6Ok9wZW4vMQ:messages"));
        assertFalse(Channels.RoomMessagesChannel.guardedStream("rooms"));
        assertFalse(Channels.RoomMessagesChannel.guardedStream("Z2lk:rooms"));
        assertFalse(Channels.RoomMessagesChannel.guardedStream("Z2lk:messages:more"));
        assertFalse(Channels.RoomMessagesChannel.guardedStream(""));
        assertTrue(Channels.RoomMessagesChannel.guardedStream(":messages"));
    }

    /// The signed value is URL-escaped (`a%2Bb%3D`), as Rack writes it, among other cookies.
    @Test
    void readsTheEscapedSessionCookieAmongOthers() throws Exception {
        app = start();
        assertEquals(WELCOME, app.connectWithCookie("x=1; " + app.cookieFor(app.david) + "; y=2").next());
    }

    // -- The endpoint -----------------------------------------------------------------------------------

    @Test
    void nonWebsocketRequestsGetRails404() throws Exception {
        app = start();
        HttpResponse<String> response = HttpClient.newHttpClient()
            .send(HttpRequest.newBuilder(URI.create(app.origin + "/cable")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        assertEquals("text/plain; charset=utf-8", response.headers().firstValue("content-type").orElse(null));
        assertEquals("Page not found", response.body());
    }

    @Test
    void crossOriginUpgradesGetRails404() throws Exception {
        app = start();
        assertRejectedWith404(() -> app.connectWith("session_token=1", "http://evil.example", List.of("actioncable-v1-json")));
    }

    @Test
    void sameOriginUnderAssumeSslMeansHttps() throws Exception {
        app = start(true);
        assertRejectedWith404(() -> app.connectWith(app.cookieFor(app.david), app.origin, List.of("actioncable-v1-json")));
        var client = app.connectWith(app.cookieFor(app.david), app.origin.replace("http://", "https://"), List.of("actioncable-v1-json"));
        assertEquals(WELCOME, client.next());
    }

    /// Vert.x refuses a handshake it can't complete (no key, no `Connection: Upgrade`) with its own
    /// 400, where Rails answers 404.
    @Test
    void incompleteUpgradesGetVertx400() throws Exception {
        app = start();
        try (var socket = new java.net.Socket("127.0.0.1", app.port)) {
            String host = "127.0.0.1:" + app.port;
            socket.getOutputStream().write(("GET /cable HTTP/1.1\r\nHost: " + host + "\r\nUpgrade: websocket\r\nOrigin: http://" + host
                + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                java.nio.charset.StandardCharsets.US_ASCII)).readLine();
            assertEquals("HTTP/1.1 400 Bad Request", status);
        }
    }

    private interface Connect {
        void run() throws Exception;
    }

    private static void assertRejectedWith404(Connect connect) throws Exception {
        try {
            connect.run();
            fail("expected the upgrade to be refused");
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof UpgradeRejectedException rejected)) throw e;
            assertEquals(404, rejected.getStatus());
        }
    }

    @Test
    void negotiatesTheActioncableSubprotocolInTheClientsOrder() throws Exception {
        app = start();
        var client = app.connectWithCookie(app.cookieFor(app.david));
        assertEquals("actioncable-v1-json", client.ws.subProtocol());
        assertEquals(WELCOME, client.next());
        var unsupported = app.connectWith(app.cookieFor(app.david), app.origin, List.of("actioncable-unsupported", "actioncable-v1-json"));
        assertEquals("actioncable-unsupported", unsupported.ws.subProtocol());
    }

    @Test
    void unauthorizedConnectionsAreToldNotToReconnectAndClosed() throws Exception {
        app = start();
        var client = app.connectWithCookie(null);
        assertEquals(UNAUTHORIZED, client.next());
        assertEquals(CLOSE + 1000, client.next());
    }

    // -- Subscriptions ------------------------------------------------------------------------------------

    @Test
    void subscribeConfirmsAndDuplicatesAreIgnored() throws Exception {
        app = start();
        var client = app.connect(app.david);
        String room = roomIdentifier("RoomChannel", app.designers.id());
        client.confirm(room);
        // Same identifier string: no reply at all.
        client.subscribe(room);
        String heartbeat = identifier("channel", "HeartbeatChannel");
        client.confirm(heartbeat);
        // A differently spelled identifier for the same room is a separate subscription.
        String respelled = "{\"room_id\":" + app.designers.id() + ",\"channel\":\"RoomChannel\"}";
        client.confirm(respelled);
    }

    @Test
    void rejectionRunsUnsubscribedAndCanBeRetried() throws Exception {
        app = start();
        var log = new java.util.concurrent.CopyOnWriteArrayList<String>();
        app.cable.register("EchoChannel", () -> new EchoChannel(log));
        var client = app.connect(app.david);
        String room = identifier("channel", "EchoChannel", "room_id", 2);
        client.reject(room);
        assertEquals(List.of("subscribed rejected=true", "unsubscribed rejected=true"), log);
        // The rejected subscription was removed, so subscribing again is answered again.
        client.reject(room);
    }

    @Test
    void unknownChannelsAndMalformedCommandsGetNoReply() throws Exception {
        app = start();
        var client = app.connect(app.david);
        client.subscribe(identifier("channel", "NopeChannel"));
        client.subscribe("not json");
        client.sendRaw("not json");
        client.send(Map.of("command", "dance"));
        String room = roomIdentifier("RoomChannel", app.designers.id());
        client.unsubscribe(room);
        client.perform(room, "{\"action\":\"echo\"}");
        client.ws.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(new byte[] {1, 2}));
        client.assertSilent();
        // The connection is still usable afterwards.
        client.confirm(room);
    }

    @Test
    void leadingColonsResolveLikeSafeConstantize() throws Exception {
        app = start();
        var client = app.connect(app.david);
        client.confirm(identifier("channel", "::HeartbeatChannel"));
    }

    @Test
    void broadcastsReachSubscribersAsEscapedJson() throws Exception {
        app = start();
        var client = app.connect(app.david);
        String room = roomIdentifier("RoomChannel", app.designers.id());
        client.confirm(room);
        String gid = app.roomGid(app.designers);
        assertEquals(1, app.cable.deliver("room:" + gid, "{\"roomId\":1}"));
        assertEquals(delivery(room, "{\"roomId\":1}"), client.next());
        app.cable.broadcast("room:" + gid, Json.encode("<b>&</b>"));
        assertEquals(delivery(room, "\"\\u003cb\\u003e\\u0026\\u003c/b\\u003e\""), client.next());
    }

    @Test
    void performDispatchesActionsAndDefaultsToReceive() throws Exception {
        app = start();
        app.cable.register("EchoChannel", () -> new EchoChannel(new java.util.ArrayList<>()));
        var client = app.connect(app.david);
        var other = app.connect(app.kevin);
        String room = identifier("channel", "EchoChannel", "room_id", 1);
        client.confirm(room);
        other.confirm(room);

        client.perform(room, "{\"action\":\"echo\",\"text\":\"hi\"}");
        assertEquals(delivery(room, "{\"action\":\"echo\",\"text\":\"hi\"}"), client.next());
        client.perform(room, "{\"text\":\"hi\"}");
        assertEquals(delivery(room, "{\"received\":{\"text\":\"hi\"}}"), client.next());
        client.perform(room, "{\"action\":\"\",\"text\":\"hi\"}");
        assertEquals(delivery(room, "{\"received\":{\"action\":\"\",\"text\":\"hi\"}}"), client.next());

        client.perform(room, "{\"action\":\"start\"}");
        String typing = delivery(room, "{\"action\":\"start\",\"user\":{\"id\":" + app.david + "}}");
        assertEquals(typing, client.next());
        assertEquals(typing, other.next());

        client.perform(room, "{\"action\":\"not_an_action\"}");
        client.perform(room, "{\"action\":1}");
        client.perform(room, "not json");
        client.assertSilent();
    }

    @Test
    void unsubscribeStopsDeliverySilently() throws Exception {
        app = start();
        var client = app.connect(app.david);
        String room = roomIdentifier("RoomChannel", app.designers.id());
        client.confirm(room);
        client.unsubscribe(room);
        client.assertSilent();
        assertEquals(0, app.cable.deliver("room:" + app.roomGid(app.designers), "{}"));
        client.assertSilent();
        assertEquals(0, app.cable.streamCount());
    }

    @Test
    void remoteDisconnectClosesEveryConnectionOfTheUser() throws Exception {
        app = start();
        var log = new java.util.concurrent.CopyOnWriteArrayList<String>();
        app.cable.register("EchoChannel", () -> new EchoChannel(log));
        var first = app.connect(app.david);
        var second = app.connect(app.david);
        var bystander = app.connect(app.kevin);
        String room = identifier("channel", "EchoChannel", "room_id", 1);
        first.confirm(room);

        assertEquals(2, app.cable.disconnectUser(app.david));
        for (var client : List.of(first, second)) {
            assertEquals("{\"type\":\"disconnect\",\"reason\":\"remote\",\"reconnect\":true}", client.next());
            assertEquals(CLOSE + 1000, client.next());
        }
        bystander.assertSilent();
        // Once the close handshake completes, the server unsubscribes the connection's channels.
        eventually(() -> log.contains("unsubscribed rejected=false"));

        var again = app.connect(app.david);
        app.cable.disconnectUser(app.david, false);
        assertEquals("{\"type\":\"disconnect\",\"reason\":\"remote\",\"reconnect\":false}", again.next());
    }

    @Test
    void restartClosesWithServerRestart() throws Exception {
        app = start();
        var client = app.connect(app.david);
        app.cable.restart();
        assertEquals("{\"type\":\"disconnect\",\"reason\":\"server_restart\",\"reconnect\":true}", client.next());
    }

    @Test
    void laggingSubscribersAreDisconnectedWithReconnect() throws Exception {
        app = start();
        var client = app.connect(app.david);
        String reads = identifier("channel", "ReadRoomsChannel");
        client.confirm(reads);
        for (int i = 0; i < 10_000; i++) app.cable.broadcast("user_" + app.david + "_reads", String.valueOf(i));
        int delivered = 0;
        while (true) {
            String frame = client.next();
            if (frame.contains("\"type\":\"disconnect\"")) {
                assertEquals("{\"type\":\"disconnect\",\"reason\":null,\"reconnect\":true}", frame);
                break;
            }
            delivered++;
        }
        assertTrue(delivered < 10_000, "delivered " + delivered);
    }

    @Test
    void pingsEveryThreeSecondsWithAUnixTimestamp() throws Exception {
        app = start();
        var client = app.connect(app.david);
        long started = System.nanoTime();
        String ping = client.next(false);
        assertTrue(System.nanoTime() - started <= 3_200_000_000L);
        var frame = (Map<?, ?>) Json.parse(ping);
        assertEquals(List.of("type", "message"), List.copyOf(frame.keySet()));
        assertEquals("ping", frame.get("type"));
        assertTrue(Math.abs((Long) frame.get("message") - System.currentTimeMillis() / 1000) <= 1);
    }

    @Test
    void turboStreamsChannelVerifiesAndGuardsStreamNames() throws Exception {
        app = start();
        var client = app.connect(app.david);
        String rooms = identifier("channel", "Turbo::StreamsChannel", "signed_stream_name", app.signedStreamName("rooms"));
        client.confirm(rooms);
        app.broadcasts.streamTo("rooms", Broadcasts.actionTag("remove", "list_room_1", null));
        assertEquals(delivery(rooms, "\"\\u003cturbo-stream action=\\\"remove\\\" target=\\\"list_room_1\\\"\\u003e\\u003c/turbo-stream\\u003e\""),
            client.next());

        client.reject(identifier("channel", "Turbo::StreamsChannel", "signed_stream_name", "rooms"));
        client.reject(identifier("channel", "Turbo::StreamsChannel"));
        // RoomStreamsAreAuthorized: a validly signed room message stream is still turned away.
        client.reject(identifier("channel", "Turbo::StreamsChannel", "signed_stream_name", app.signedStreamName("Z2lk", "messages")));
        assertEquals(0, app.cable.deliver("Z2lk:messages", "\"x\""));
        client.reject(identifier("channel", "Turbo::StreamsChannel", "signed_stream_name", 1));
    }

    @Test
    void aClientCloseIsAnsweredWithItsCode() throws Exception {
        app = start();
        var client = app.connect(app.david);
        client.ws.close((short) 1001);
        assertEquals(CLOSE + 1001, client.next());
    }

    @Test
    void aConnectionHoldsABoundedNumberOfSubscriptions() throws Exception {
        app = start();
        var client = app.connect(app.david);
        for (int nonce = 0; nonce < 64; nonce++) client.confirm(identifier("channel", "HeartbeatChannel", "nonce", nonce));
        client.subscribe(identifier("channel", "HeartbeatChannel", "nonce", 64));
        client.assertSilent();
        var other = app.connect(app.kevin);
        other.subscribe(identifier("channel", "HeartbeatChannel", "pad", "x".repeat(5000)));
        other.assertSilent();
    }

    /// Like `RoomChannel` plus `TypingNotificationsChannel`'s actions, logging its callbacks.
    static final class EchoChannel implements Channel {
        private final List<String> log;
        private String room;

        EchoChannel(List<String> log) {
            this.log = log;
        }

        @Override
        public void subscribed(Subscription sub) {
            if (Long.valueOf(1).equals(sub.param("room_id"))) {
                room = "echo:room-1";
                sub.streamFrom(room);
            } else {
                sub.reject();
            }
            log.add("subscribed rejected=" + sub.rejected());
        }

        @Override
        public void unsubscribed(Subscription sub) {
            log.add("unsubscribed rejected=" + sub.rejected());
        }

        @Override
        public boolean perform(String action, Map<String, Object> data, Subscription sub) {
            switch (action) {
                case "start" -> {
                    var payload = new java.util.LinkedHashMap<String, Object>();
                    payload.put("action", "start");
                    payload.put("user", Map.of("id", sub.currentUser().id()));
                    sub.cable().deliver(room, Json.encode(payload));
                }
                case "echo" -> sub.transmit(data);
                case "receive" -> sub.transmit(Map.of("received", data));
                default -> {
                    return false;
                }
            }
            return true;
        }
    }
}
