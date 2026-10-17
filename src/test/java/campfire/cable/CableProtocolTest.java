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
        assertEquals("{\"type\":\"disconnect\",\"reason\":\"remote\",\"reconnect\":\"x\"}", Protocol.disconnect(Protocol.REMOTE, "x"));
        String identifier = "{\"channel\":\"RoomChannel\",\"room_id\":1}";
        assertEquals("{\"identifier\":\"{\\\"channel\\\":\\\"RoomChannel\\\",\\\"room_id\\\":1}\",\"type\":\"confirm_subscription\"}",
            Protocol.confirmation(identifier));
        assertEquals("{\"identifier\":\"{\\\"channel\\\":\\\"RoomChannel\\\",\\\"room_id\\\":1}\",\"type\":\"reject_subscription\"}",
            Protocol.rejection(identifier));
        assertEquals("{\"identifier\":\"\\u003cx\\u003e\",\"type\":\"confirm_subscription\"}", Protocol.confirmation("<x>"));
        assertEquals("{\"identifier\":\"id\",\"message\":{\"a\":1}}", Protocol.message(Json.encode("id"), "{\"a\":1}"));
    }

    @Test
    void channelNamesAndBroadcastings() {
        assertEquals("room", Naming.channelName("RoomChannel"));
        assertEquals("typing_notifications", Naming.channelName("TypingNotificationsChannel"));
        assertEquals("turbo:streams", Naming.channelName("Turbo::StreamsChannel"));
        assertEquals("html", Naming.channelName("HTMLChannel"));
        assertEquals("html_parser", Naming.channelName("HTMLParserChannel"));
        String room = campfire.rails.GlobalId.of("Rooms::Open", 1).toParam();
        assertEquals("Z2lkOi8vY2FtcGZpcmUvUm9vbXM6Ok9wZW4vMQ", room);
        assertEquals("presence:" + room, Naming.broadcastingFor("PresenceChannel", room));
        assertEquals(room + ":messages", Broadcasts.streamName(room, "messages"));
    }

    @Test
    void castsIdsLikeActiveRecord() {
        assertEquals(5L, Channels.castId(5L));
        assertEquals(5L, Channels.castId(5.9));
        assertEquals(5L, Channels.castId("5"));
        assertEquals(12L, Channels.castId(" 12abc"));
        assertEquals(1000L, Channels.castId("1_000"));
        assertEquals(-1L, Channels.castId("-1"));
        assertNull(Channels.castId("abc"));
        assertNull(Channels.castId(""));
        assertEquals(1L, Channels.castId(true));
        assertNull(Channels.castId(null));
        assertNull(Channels.castId("99999999999999999999"));
        assertEquals(5L, Channels.castId("\u000b5"));
        assertEquals(12L, Channels.castId("0d12"));
        assertNull(Channels.castId(" 5"));
        assertEquals(Long.MIN_VALUE, Channels.castId("-9223372036854775808"));
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

    @Test
    void parsesCookiesLikeRack() {
        assertEquals("a+b/c=", Cable.cookie("x=1; session_token=a%2Bb%2Fc%3D;y=2", "session_token"));
        assertEquals("first", Cable.cookie("session_token=first; session_token=second", "session_token"));
        assertNull(Cable.cookie("other=1", "session_token"));
        assertNull(Cable.cookie(null, "session_token"));
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
        app = start(new Cable.Options(false, List.of(), true, true));
        assertRejectedWith404(() -> app.connectWith(app.cookieFor(app.david), app.origin, List.of("actioncable-v1-json")));
        var client = app.connectWith(app.cookieFor(app.david), app.origin.replace("http://", "https://"), List.of("actioncable-v1-json"));
        assertEquals(WELCOME, client.next());
    }

    @Test
    void allowedRequestOriginsAndDisabledForgeryProtection() throws Exception {
        app = start(new Cable.Options(false, List.of("https://chat.example"), true, false));
        assertEquals(WELCOME, app.connectWith(app.cookieFor(app.david), "https://chat.example", List.of("actioncable-v1-json")).next());
        app.close();
        app = start(new Cable.Options(true, List.of(), true, false));
        assertEquals(WELCOME, app.connectWith(app.cookieFor(app.david), "http://evil.example", List.of("actioncable-v1-json")).next());
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
        assertEquals(1, app.cable.broadcastTo("RoomChannel", "{\"roomId\":1}", gid));
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
        assertEquals(1, app.cable.streamCount(), "only the internal channel remains");
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
        // A non-string name raises in MessageVerifier: neither confirmed nor rejected.
        client.subscribe(identifier("channel", "Turbo::StreamsChannel", "signed_stream_name", 1));
        client.assertSilent();
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
                room = "room-1";
                sub.streamFor(room);
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
                    sub.broadcastTo(payload, room);
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
