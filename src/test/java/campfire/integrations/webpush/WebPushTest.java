package campfire.integrations.webpush;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.integrations.TestSupport;
import campfire.integrations.TestSupport.FakeResolver;
import campfire.integrations.TestSupport.FakeServer;
import campfire.integrations.TestSupport.MappingRoute;
import campfire.integrations.TestSupport.Route;
import campfire.integrations.net.Network;
import campfire.rails.Base64s;
import campfire.rails.Json;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebPushTest {
    /// `DnsTestHelper::WEB_PUSH_PUBLIC_TEST_IP`
    static final String PUBLIC_IP = "142.250.185.206";
    /// parity/.env.reference in the Rust port
    static final String VAPID_PUBLIC_KEY = "BEYXTBB5_jNhNzXDmx5KEU55Vbbd-u--Lk9rM5OFQvUkPIBwZJ9QzAq0zdEzFw6yTV8cTriz_qYBVicY02_VxTQ=";
    static final String VAPID_PRIVATE_KEY = "qfXLHghuG1rSHZUVo9SscNRI-0EIHRbIrfeGCqbAwak=";
    static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc";

    @SuppressWarnings("unchecked")
    static final Map<String, Object> EXPECTED = (Map<String, Object>) TestSupport.json("web_push_expected.json");

    static byte[] b64(String s) {
        return Base64s.urlsafeDecode(s);
    }

    static String e64(byte[] b) {
        return Base64s.urlsafeEncodeUnpadded(b);
    }

    /// What a user agent does with the message (RFC 8291 section 3.4): the record size and the
    /// padded plaintext.
    record Opened(int recordSize, byte[] plaintext) {}

    static Opened decrypt(byte[] body, ECPrivateKey receiver, byte[] receiverPublic, byte[] auth) throws Exception {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int recordSize = ByteBuffer.wrap(body, 16, 4).getInt();
        int idLength = body[20] & 0xff;
        byte[] serverPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
        byte[] ciphertext = Arrays.copyOfRange(body, 21 + idLength, body.length);
        byte[] shared = P256.ecdh(receiver, P256.decodePoint(serverPublic));
        byte[] info = Encryption.concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), receiverPublic, serverPublic);
        byte[] prk = Encryption.hkdf(auth, shared, info, 32);
        byte[] key = Encryption.hkdf(salt, prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = Encryption.hkdf(salt, prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return new Opened(recordSize, cipher.doFinal(ciphertext));
    }

    /// A browser's subscription keys.
    static final class Receiver {
        final ECPrivateKey key;
        final byte[] publicKey;
        final byte[] auth = new byte[16];

        Receiver() {
            var pair = P256.generate();
            key = (ECPrivateKey) pair.getPrivate();
            publicKey = P256.encodeUncompressed((ECPublicKey) pair.getPublic());
            new SecureRandom().nextBytes(auth);
        }

        WebPush.Subscription subscription(long id, String endpoint) {
            return new WebPush.Subscription(id, endpoint, e64(publicKey), e64(auth));
        }

        String open(byte[] body) throws Exception {
            byte[] plaintext = decrypt(body, key, publicKey, auth).plaintext();
            assertArrayEquals(new byte[] {2, 0}, Arrays.copyOfRange(plaintext, plaintext.length - 2, plaintext.length), "gem padding");
            return new String(plaintext, 0, plaintext.length - 2, StandardCharsets.UTF_8);
        }
    }

    // RFC 8291, section 5.
    static final String PLAINTEXT = "V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24";
    static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String MESSAGE = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void matchesTheRfc8291TestVector() throws Exception {
        byte[] body = Encryption.encryptWith(b64(PLAINTEXT), UA_PUBLIC, AUTH, P256.privateKey(b64(AS_PRIVATE)), Arrays.copyOfRange(b64(MESSAGE), 21, 86), b64(SALT), 4096,
            new byte[] {2});
        assertEquals(MESSAGE, e64(body));

        var opened = decrypt(b64(MESSAGE), P256.privateKey(b64(UA_PRIVATE)), b64(UA_PUBLIC), b64(AUTH));
        assertEquals(4096, opened.recordSize());
        assertArrayEquals(Encryption.concat(b64(PLAINTEXT), new byte[] {2}), opened.plaintext());
    }

    @Test
    void roundTripsWithTheGemsFraming() throws Exception {
        var receiver = new Receiver();
        var s = receiver.subscription(1, ENDPOINT);
        byte[] body = Encryption.encrypt("{\"title\":\"hi\"}".getBytes(StandardCharsets.UTF_8), s.p256dh(), s.auth());
        var opened = decrypt(body, receiver.key, receiver.publicKey, receiver.auth);
        assertArrayEquals("{\"title\":\"hi\"}\2\0".getBytes(StandardCharsets.UTF_8), opened.plaintext());
        assertEquals(body.length - 86, opened.recordSize());
    }

    @Test
    void decryptsWhatTheGemEncrypted() throws Exception {
        byte[] body = b64((String) EXPECTED.get("ciphertext"));
        var opened = decrypt(body, P256.privateKey(b64((String) EXPECTED.get("receiver_private_key"))), b64((String) EXPECTED.get("p256dh")),
            b64((String) EXPECTED.get("auth")));
        assertEquals(body.length - 86, opened.recordSize());
        assertArrayEquals(Encryption.concat(((String) EXPECTED.get("message")).getBytes(StandardCharsets.UTF_8), new byte[] {2, 0}), opened.plaintext());
    }

    @Test
    void rejectsWhatTheGemRejects() {
        var ok = new Receiver().subscription(1, ENDPOINT).p256dh();
        assertFalse(assertThrows(Encryption.EncryptionException.class, () -> Encryption.encrypt("m".getBytes(), null, "YXV0aA")).invalidKey);
        assertFalse(assertThrows(Encryption.EncryptionException.class, () -> Encryption.encrypt("m".getBytes(), ok, "")).invalidKey);
        assertFalse(assertThrows(Encryption.EncryptionException.class, () -> Encryption.encrypt("m".getBytes(), "not base64!", "YXV0aA")).invalidKey);
        assertTrue(assertThrows(Encryption.EncryptionException.class, () -> Encryption.encrypt("m".getBytes(), "dGVzdF9rZXk", "YXV0aA")).invalidKey);
        // a 65-byte uncompressed encoding of a point that isn't on the curve
        byte[] offCurve = new byte[65];
        offCurve[0] = 4;
        offCurve[64] = 1;
        assertTrue(assertThrows(Encryption.EncryptionException.class, () -> Encryption.encrypt("m".getBytes(), e64(offCurve), "YXV0aA")).invalidKey);
        assertFalse(assertThrows(Encryption.EncryptionException.class, () -> Encryption.encrypt(new byte[4100], ok, "YXV0aA")).invalidKey);
    }

    @Test
    void encodesTheMessageLikeJsonGenerate() {
        assertEquals(EXPECTED.get("message"),
            WebPush.encodedMessage("Designers <&> \"quotes\" é 😀", "Kevin: line\nbreak\ttab   \u001f / \\ ", "/rooms/1", 3, WebPush.ACCOUNT_LOGO_PATH));
    }

    @Test
    void truncatesByJsonEscapedLength() {
        assertEquals("short", WebPush.truncateJsonString("short", 5));
        assertEquals("lo…", WebPush.truncateJsonString("longer", 5));
        assertEquals("\u0001\u0001…", WebPush.truncateJsonString("\u0001".repeat(10), 16));
        assertEquals("\"…", WebPush.truncateJsonString("\"\"\"", 5));
        assertEquals("😀…", WebPush.truncateJsonString("😀😀", 7));
    }

    @Test
    void theLongestPayloadFitsAPushMessage() throws Exception {
        var receiver = new Receiver();
        var s = receiver.subscription(1, ENDPOINT);
        String message = WebPush.encodedMessage("\"".repeat(1000), "\u0001".repeat(5000), "/rooms/" + Long.MIN_VALUE, Long.MIN_VALUE, "/account/logo");
        assertEquals(message, receiver.open(Encryption.encrypt(message.getBytes(StandardCharsets.UTF_8), s.p256dh(), s.auth())));
    }

    static Vapid vapid() throws Exception {
        return Vapid.of(WebPush.DEFAULT_SUBJECT, VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY);
    }

    @Test
    void signsTheVapidHeaderLikeTheGem() throws Exception {
        long now = ((Number) EXPECTED.get("now")).longValue();
        String authorization = vapid().authorization("https://fcm.googleapis.com", now);
        assertTrue(authorization.startsWith("vapid t="));
        String[] tk = authorization.substring("vapid t=".length()).split(",k=");
        assertEquals(EXPECTED.get("authorization_k"), tk[1]);
        String[] segments = tk[0].split("\\.");
        assertEquals(EXPECTED.get("jwt_header_segment"), segments[0]);
        assertEquals(EXPECTED.get("jwt_payload_segment"), segments[1]);

        // ES256: a raw r‖s signature that the public key verifies
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(P256.decodePoint(b64(VAPID_PUBLIC_KEY)));
        verifier.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
        assertEquals(64, b64(segments[2]).length);
        assertTrue(verifier.verify(b64(segments[2])));
    }

    @Test
    void signsWithTheConfiguredSubject() throws Exception {
        String authorization = Vapid.of("mailto:ops@example.com", VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY).authorization("https://x", 0);
        String payload = new String(b64(authorization.substring(8).split(",k=")[0].split("\\.")[1]), StandardCharsets.UTF_8);
        assertEquals("mailto:ops@example.com", ((Map<?, ?>) Json.parse(payload)).get("sub"));
    }

    @Test
    void rejectsBadVapidKeysUpFront() throws Exception {
        String otherPublic = e64(new Receiver().publicKey);
        assertThrows(Vapid.VapidException.class, () -> Vapid.of("s", "", VAPID_PRIVATE_KEY));
        assertThrows(Vapid.VapidException.class, () -> Vapid.of("s", "dGVzdF9rZXk", VAPID_PRIVATE_KEY));
        assertThrows(Vapid.VapidException.class, () -> Vapid.of("s", VAPID_PUBLIC_KEY, "not base64!"));
        byte[] tooBig = new byte[32];
        Arrays.fill(tooBig, (byte) 0xff);
        assertThrows(Vapid.VapidException.class, () -> Vapid.of("s", VAPID_PUBLIC_KEY, e64(tooBig)));
        assertThrows(Vapid.VapidException.class, () -> Vapid.of("s", otherPublic, VAPID_PRIVATE_KEY));
        Vapid.of("s", VAPID_PUBLIC_KEY.replace("=", ""), VAPID_PRIVATE_KEY.replace("=", ""));

        assertFalse(new WebPush(null, VAPID_PRIVATE_KEY, null, TestSupport.offline()).enabled());
        assertFalse(new WebPush("dGVzdF9rZXk", VAPID_PRIVATE_KEY, null, TestSupport.offline()).enabled());
        assertTrue(new WebPush(VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, null, TestSupport.offline()).enabled());
    }

    /// A push service answering `status` on TLS, with fcm.googleapis.com at a fake public address.
    record PushService(FakeServer server, FakeResolver resolver, MappingRoute route, Network net) implements AutoCloseable {
        static PushService start(int status, String reason) throws Exception {
            var routes = new ArrayList<Route>();
            for (String path : List.of("/fcm/send/abc", "/fcm/send/123", "/fcm/send/456")) {
                var route = new Route("POST", "*", path, status);
                route.reason = reason;
                routes.add(route);
            }
            var server = FakeServer.startTls(routes);
            var resolver = new FakeResolver().set("fcm.googleapis.com", PUBLIC_IP);
            var route = new MappingRoute(Set.copyOf(TestSupport.ips(PUBLIC_IP)), server.port());
            return new PushService(server, resolver, route, TestSupport.network(resolver, route));
        }

        WebPush webPush() {
            return webPush(id -> {});
        }

        WebPush webPush(LongConsumer invalidSubscriptionHandler) {
            return new WebPush(VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, null, net, invalidSubscriptionHandler);
        }

        @Override
        public void close() throws Exception {
            server.close();
        }
    }

    @Test
    void deliversToThePinnedAddressWithTheGemsHeaders() throws Exception {
        try (var service = PushService.start(201, "Created"); var push = service.webPush()) {
            var receiver = new Receiver();
            var result = push.deliverNow(receiver.subscription(1, ENDPOINT), "t", "b", "/", 0, WebPush.ACCOUNT_LOGO_PATH);
            assertEquals(new WebPush.Result.Delivered(201), result);
            assertEquals(List.of("fcm.googleapis.com"), service.resolver().lookups);
            assertEquals(PUBLIC_IP, service.route().dialed.getFirst().getAddress().getHostAddress());
            assertEquals(443, service.route().dialed.getFirst().getPort());

            var request = service.server().received.getFirst();
            assertEquals("POST", request.method());
            assertEquals("/fcm/send/abc", request.target());
            Set<String> names = request.headers().stream().map(h -> h[0].toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            assertEquals(Set.of("content-type", "ttl", "urgency", "content-encoding", "content-length", "authorization", "accept-encoding", "accept",
                "user-agent", "connection", "host"), names);
            @SuppressWarnings("unchecked")
            var expectedHeaders = (List<List<String>>) EXPECTED.get("headers");
            for (var header : expectedHeaders) {
                if (!header.get(0).equals("Content-Length")) assertEquals(header.get(1), request.header(header.get(0)), header.get(0));
            }
            assertEquals(Integer.toString(request.body().length), request.header("Content-Length"));
            assertEquals("fcm.googleapis.com", request.header("Host"));
            assertTrue(request.header("Authorization").startsWith("vapid t=" + EXPECTED.get("jwt_header_segment") + "."));
            assertTrue(request.header("Authorization").endsWith(",k=" + EXPECTED.get("authorization_k")));
            assertEquals(WebPush.encodedMessage("t", "b", "/", 0, WebPush.ACCOUNT_LOGO_PATH), receiver.open(request.body()));
        }
    }

    @Test
    void skipsEndpointsItMayNotDeliverTo() throws Exception {
        try (var service = PushService.start(201, "Created"); var push = service.webPush()) {
            service.resolver().set("updates.push.services.mozilla.com", "10.0.0.5");
            var receiver = new Receiver();
            for (String endpoint : List.of("https://updates.push.services.mozilla.com/wpush/v2/x", "https://web.push.apple.com/QaBC123",
                "https://attacker.example.com/collect", "https://fcm.googleapis.com:22/fcm/send/abc", "http://fcm.googleapis.com/fcm/send/abc",
                "https://evilfcm.googleapis.com.attacker.example/webhook")) {
                assertEquals(new WebPush.Result.Skipped(), push.deliverNow(receiver.subscription(1, endpoint), "t", "b", "/", 0, "/"), endpoint);
            }
            assertTrue(service.server().received.isEmpty());
            assertEquals(List.of("updates.push.services.mozilla.com", "web.push.apple.com"), service.resolver().lookups);
        }
    }

    @Test
    void validatesEndpointsLikePushSubscription() {
        var resolver = new FakeResolver().set("fcm.googleapis.com", PUBLIC_IP).set("updates.push.services.mozilla.com", "10.0.0.5");
        assertTrue(WebPush.validEndpoint(ENDPOINT, resolver));
        assertFalse(WebPush.validEndpoint("", resolver));
        assertFalse(WebPush.validEndpoint("https://fcm.googleapis.com/a b", resolver));
        assertFalse(WebPush.validEndpoint("http://fcm.googleapis.com/x", resolver));
        assertFalse(WebPush.validEndpoint("https://fcm.googleapis.com:8443/x", resolver));
        assertFalse(WebPush.validEndpoint("https://evil.example/x", resolver));
        assertFalse(WebPush.validEndpoint("https://updates.push.services.mozilla.com/x", resolver));
        assertTrue(WebPush.validEndpoint("https://FCM.googleapis.com/x", resolver.set("FCM.googleapis.com", PUBLIC_IP)));
    }

    /// The statuses the gem tells apart; only 404 and 410 say the subscription is gone.
    @Test
    void classifiesPushServiceResponses() throws Exception {
        var subscription = new Receiver().subscription(1, ENDPOINT);
        record Case(int status, String reason, WebPush.DeliveryException.Kind kind) {}
        var gone = WebPush.DeliveryException.Kind.SUBSCRIPTION_GONE;
        var response = WebPush.DeliveryException.Kind.RESPONSE;
        for (var c : List.of(new Case(410, "Gone", gone), new Case(404, "Not Found", gone), new Case(403, "Forbidden", response),
            new Case(400, "UnauthorizedRegistration", response), new Case(400, "Bad Request", response), new Case(413, "Payload Too Large", response),
            new Case(429, "Too Many Requests", response), new Case(503, "Service Unavailable", response), new Case(302, "Found", response))) {
            try (var service = PushService.start(c.status(), c.reason()); var push = service.webPush()) {
                var failed = assertInstanceOf(WebPush.Result.Failed.class, push.deliverNow(subscription, "t", "b", "/", 0, "/"));
                assertEquals(c.kind(), failed.error().kind, c.toString());
                assertEquals(c.status(), failed.error().status, c.toString());
                assertEquals(c.kind() == gone, failed.error().invalidatesSubscription(), c.toString());
            }
        }
    }

    @Test
    void onlyTheSubscriptionsOwnFaultsInvalidateIt() throws Exception {
        try (var service = PushService.start(201, "Created"); var push = service.webPush()) {
            var badKey = new WebPush.Subscription(1, ENDPOINT, "dGVzdF9rZXk", "dGVzdF9hdXRo");
            var error = assertInstanceOf(WebPush.Result.Failed.class, push.deliverNow(badKey, "t", "b", "/", 0, "/")).error();
            assertEquals(WebPush.DeliveryException.Kind.INVALID_SUBSCRIPTION_KEY, error.kind);
            assertTrue(error.invalidatesSubscription());

            // A certificate that doesn't verify may be our fault (an empty CA store, a skewed clock).
            var untrustingNet = TestSupport.network(service.resolver(), service.route(), TestSupport.untrustingTls());
            try (var untrusted = new WebPush(VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY, null, untrustingNet)) {
                var tls = assertInstanceOf(WebPush.Result.Failed.class, untrusted.deliverNow(new Receiver().subscription(1, ENDPOINT), "t", "b", "/", 0, "/"));
                assertEquals(WebPush.DeliveryException.Kind.TLS, tls.error().kind);
                assertFalse(tls.error().invalidatesSubscription());
            }

            var blank = new WebPush.Subscription(1, ENDPOINT, "", "dGVzdF9hdXRo");
            var argument = assertInstanceOf(WebPush.Result.Failed.class, push.deliverNow(blank, "t", "b", "/", 0, "/")).error();
            assertEquals(WebPush.DeliveryException.Kind.ARGUMENT, argument.kind);
            assertFalse(argument.invalidatesSubscription());
        }
    }

    @Test
    void testNotification() throws Exception {
        try (var service = PushService.start(201, "Created"); var push = service.webPush()) {
            var receiver = new Receiver();
            String body = UUID.randomUUID().toString();
            push.deliverNow(receiver.subscription(1, ENDPOINT), "Campfire Test", body, "http://example.com/users/me/push_subscriptions", 4,
                WebPush.ACCOUNT_LOGO_PATH);
            var message = (Map<?, ?>) Json.parse(receiver.open(service.server().received.getFirst().body()));
            assertEquals("Campfire Test", message.get("title"));
            var options = (Map<?, ?>) message.get("options");
            assertEquals(body, options.get("body"));
            assertEquals("/account/logo", options.get("icon"));
            var data = (Map<?, ?>) options.get("data");
            assertEquals("http://example.com/users/me/push_subscriptions", data.get("path"));
            assertEquals(4L, ((Number) data.get("badge")).longValue());
        }
    }

    @Test
    void thePoolInvalidatesExpiredSubscriptionsOnly() throws Exception {
        var invalidated = new CopyOnWriteArrayList<Long>();
        var latch = new CountDownLatch(2);
        try (var gone = PushService.start(410, "Gone"); var push = gone.webPush(id -> {
            invalidated.add(id);
            latch.countDown();
        })) {
            var receiver = new Receiver();
            push.deliverLater(receiver.subscription(7, ENDPOINT), "t", "b", "/", 1, "/");
            push.deliverLater(receiver.subscription(8, "https://fcm.googleapis.com/fcm/send/123"), "t", "b", "/", 1, "/");
            assertTrue(latch.await(10, TimeUnit.SECONDS));
            assertEquals(Set.of(7L, 8L), Set.copyOf(invalidated));
        }
        var notInvalidated = new CopyOnWriteArrayList<Long>();
        try (var unavailable = PushService.start(503, "Service Unavailable"); var push = unavailable.webPush(notInvalidated::add)) {
            push.deliverLater(new Receiver().subscription(9, ENDPOINT), "t", "b", "/", 1, "/");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (push.pending() > 0 && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(0, push.pending());
            assertEquals(1, unavailable.server().received.size());
            push.shutdown();
            assertTrue(notInvalidated.isEmpty());
        }
    }

    @Test
    void disabledPushDeliversNothing() {
        try (var push = new WebPush(null, null, null, TestSupport.offline())) {
            assertFalse(push.enabled());
            push.deliverLater(new Receiver().subscription(1, ENDPOINT), "t", "b", "/", 0, "/");
            assertEquals(0, push.pending());
        }
    }
}
