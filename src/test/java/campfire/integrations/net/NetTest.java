package campfire.integrations.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.integrations.TestSupport;
import campfire.integrations.TestSupport.FakeResolver;
import campfire.integrations.TestSupport.FakeServer;
import campfire.integrations.TestSupport.MappingRoute;
import campfire.integrations.TestSupport.Route;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NetTest {
    private static boolean blocked(String ip) {
        return Guard.blocked(Guard.parseAddress(ip));
    }

    @Test
    void classifiesAddressesLikeSurfguard() {
        for (String ip : List.of("0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "168.63.129.16", "169.254.169.254", "172.16.0.0",
            "172.31.255.255", "192.0.0.8", "192.0.2.1", "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "240.0.0.1", "255.255.255.255", "::", "::1", "::ffff:192.168.1.1", "::ffff:8.8.8.8", "::8.8.8.8", "64:ff9b::a00:1", "64:ff9b:1::1",
            "::ffff:0:a00:1", "fc00::1", "fd00::1", "fe80::1", "fec0::1", "ff02::1", "2001::1", "2001:db8::1", "2002::1", "3fff::1", "5f00::1",
            "100::1", "2001:2::1", "4000::1", "2001:10::1")) {
            assertTrue(blocked(ip), ip + " should be blocked");
        }
        for (String ip : List.of("8.8.8.8", "1.1.1.1", "93.184.216.34", "142.250.185.206", "172.32.0.1", "100.128.0.1", "192.0.1.1",
            "2606:2800:220:1:248:1893:25c8:1946", "2a00:1450:4001:82a::200e", "2001:3::1", "2001:4:112::1", "64:ff9b::808:808",
            "::ffff:0:808:808", "2c0f:ffff::1")) {
            assertFalse(blocked(ip), ip + " should be public");
        }
    }

    @Test
    void inetAtonForms() {
        var loopback = Guard.parseAddress("127.0.0.1");
        for (String form : List.of("127.1", "0x7f.1", "2130706433", "0177.0.0.01")) assertEquals(loopback, Guard.literal(form), form);
        assertEquals(Guard.parseAddress("10.0.1.2"), Guard.literal("10.0.258"));
        for (String bad : List.of("09.1.1.1", "256.1.1.1", "1.2.3.4.", "www.example.com", "[1.2.3.4]", "fe80::1%1")) assertNull(Guard.literal(bad), bad);
        // IPv4-mapped literals stay IPv6, so they're classified as such.
        assertEquals(16, Guard.parseAddress("::ffff:8.8.8.8").getAddress().length);
    }

    @Test
    void resolvesHostsLikeThePrivateNetworkGuard() throws Exception {
        var resolver = new FakeResolver().set("www.example.com", "93.184.216.34")
            .set("mixed.example", "10.0.0.1", "2606:2800:220:1:248:1893:25c8:1946", "::1", "93.184.216.39")
            .set("private.example", "192.168.1.10");
        assertEquals(Guard.parseAddress("93.184.216.34"), Guard.resolve(resolver, "www.example.com"));
        assertEquals(TestSupport.ips("93.184.216.39", "2606:2800:220:1:248:1893:25c8:1946"), Guard.resolvePublicIps(resolver, "mixed.example"));
        assertThrows(Guard.Violation.class, () -> Guard.resolve(resolver, "private.example"));
        assertThrows(Guard.Unresolvable.class, () -> Guard.resolve(resolver, "nowhere.example"));
        assertEquals(Guard.parseAddress("8.8.8.8"), Guard.resolve(resolver, "8.8.8.8"));
        assertEquals(Guard.parseAddress("2606:2800:220:1:248:1893:25c8:1946"), Guard.resolve(resolver, "[2606:2800:220:1:248:1893:25c8:1946]"));
        for (String host : List.of("127.0.0.1", "0x7f.1", "2130706433", "[::1]", "::1", "[fd00::1]", "under_score.example", "", "a..b", "1.2.3.4.",
            "01.2.3.4.", "host%eth0", "exämple.com", "-lead.example", "[v1.x]")) {
            assertThrows(Guard.Violation.class, () -> Guard.resolve(resolver, host), host);
        }
        assertEquals(List.of("www.example.com", "mixed.example", "private.example", "nowhere.example"), resolver.lookups);
    }

    @Test
    void parsesUrlsLikeRuby() throws Exception {
        var url = Url.parse("https://x.com/dhh/status/1?s=20");
        assertEquals("x.com", url.host());
        assertEquals("/dhh/status/1?s=20", url.requestUri());
        assertEquals(443, url.port());
        for (String bad : List.of("http://exa mple.com/ ", "http://www.example.com/é", "https://", "http://x.com/a|b", "http://x.com/#a#b")) {
            assertThrows(URISyntaxException.class, () -> Url.parse(bad), bad);
        }
        assertNull(Url.parse("https:/rooms/1").host());
        assertNull(Url.parse("https:rooms/1").host());
        assertNull(Url.parse("mailto:foo").host());
        assertEquals("[::1]", Url.parse("http://[::1]/x").host());
        assertEquals("::1", Url.parse("http://[::1]/x").hostname());
        assertEquals("https", Url.parse("HTTPS://x.com/a").scheme());
        assertEquals(80, Url.parse("http://x.com:/a").port());
        assertEquals(-1, Url.parse("ftp://x.com/a").port());
        assertEquals("/", Url.parse("http://h").requestUri());
        assertEquals("/query?a=1&b=2", Url.parse("http://h/query?a=1&b=2#frag").requestUri());
        assertEquals("https://fxtwitter.com/dhh/status/1?s=20#top", Url.parse("https://x.com/dhh/status/1?s=20#top").withHost("fxtwitter.com").toString());
    }

    /// Webhook URLs an administrator may have stored, which Ruby's `URI.parse` reads: hosts
    /// `java.net.URI` won't take as host names, and query characters Ruby escapes.
    @Test
    void parsesStoredWebhookUrls() throws Exception {
        record Case(String url, String host, int port, String requestUri) {}
        for (var c : List.of(new Case("http://bot_host:3000/webhook", "bot_host", 3000, "/webhook"), new Case("http://my_bot/hook", "my_bot", 80, "/hook"),
            new Case("https://user:secret@bot_host.internal:8443/a/b?x=1", "bot_host.internal", 8443, "/a/b?x=1"),
            new Case("http://localhost:3000/hook?token=a|b&q=a b", "localhost", 3000, "/hook?token=a%7Cb&q=a%20b"),
            new Case("http://example.com/hook?list=[1,2]&json={\"a\":1}", "example.com", 80, "/hook?list=[1,2]&json=%7B%22a%22:1%7D"),
            new Case("http://example.com/hook?x=100%25&y=50%", "example.com", 80, "/hook?x=100%25&y=50%25"),
            new Case("http://10.0.0.5:8080/bots/1/messages", "10.0.0.5", 8080, "/bots/1/messages"),
            new Case("http://[fd00::5]:8080/hook", "[fd00::5]", 8080, "/hook"),
            new Case("https://hooks.example.com/services/T000/B000/XXXX", "hooks.example.com", 443, "/services/T000/B000/XXXX"),
            new Case("http://bot.example.com/hook#section", "bot.example.com", 80, "/hook"),
            new Case("HTTP://Bot.Example.com", "Bot.Example.com", 80, "/"))) {
            var url = Url.parse(c.url());
            assertTrue(url.isHttp(), c.url());
            assertEquals(c.host(), url.host(), c.url());
            assertEquals(c.port(), url.port(), c.url());
            assertEquals(c.requestUri(), url.requestUri(), c.url());
        }
    }

    private static final PinnedHttp.Timeouts TIMEOUTS = new PinnedHttp.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10));

    private static MappingRoute routeTo(int port) {
        return new MappingRoute(Set.copyOf(TestSupport.ips("93.184.216.34")), port);
    }

    private static Network networkTo(FakeServer server) {
        return TestSupport.network(new FakeResolver(), routeTo(server.port()));
    }

    private static PinnedHttp.Response get(Network net, boolean https, String path) throws Exception {
        return get(net, https, "www.example.com", path, TIMEOUTS);
    }

    private static PinnedHttp.Response get(Network net, boolean https, String host, String path, PinnedHttp.Timeouts timeouts) throws Exception {
        var target = new PinnedHttp.Target(https, host, https ? 443 : 80, Guard.parseAddress("93.184.216.34"));
        return PinnedHttp.exchange(net, target, new PinnedHttp.Request("GET", path), timeouts);
    }

    /// A gigabyte packed into a megabyte stops inflating just past the limit.
    @Test
    void stopsReadingAGzipBombAtTheLimit() throws Exception {
        var routes = List.of(
            new Route("GET", "*", "/bomb", 200).header("Content-Encoding", "gzip").body(TestSupport.gzipBomb(1024)),
            new Route("GET", "*", "/small", 200).header("Content-Encoding", "gzip").body(TestSupport.gzipBomb(3)));
        try (var server = FakeServer.start(routes)) {
            var net = networkTo(server);
            long started = System.nanoTime();
            try (var response = get(net, false, "/bomb")) {
                assertNull(response.readBody(5 * 1024 * 1024));
            }
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos());
            try (var response = get(net, false, "/small")) {
                assertArrayEquals(new byte[3 * 1024 * 1024], response.readBody(5 * 1024 * 1024));
            }
            var request = server.received.getFirst();
            assertEquals("www.example.com", request.header("Host"));
            assertEquals(PinnedHttp.NET_HTTP_ACCEPT_ENCODING, request.header("Accept-Encoding"));
            assertEquals("Ruby", request.header("User-Agent"));
            assertNull(request.header("Content-Length"));
        }
    }

    @Test
    void readsChunkedBodies() throws Exception {
        var route = new Route("GET", "*", "/", 200).body("x".repeat(200_000));
        route.chunked = true;
        try (var server = FakeServer.start(List.of(route)); var response = get(networkTo(server), false, "/")) {
            assertEquals(200, response.status);
            assertEquals("Status", response.reason);
            assertEquals(200_000, response.readBody(1 << 20).length);
        }
    }

    /// TLS to the pinned address, with the certificate verified against the host name (SNI).
    @Test
    void verifiesTlsAgainstTheHostName() throws Exception {
        try (var server = FakeServer.startTls(List.of(new Route("GET", "*", "/", 200).body("hi")))) {
            var net = networkTo(server);
            try (var response = get(net, true, "/")) {
                assertEquals("hi", new String(response.readBody(100)));
            }
            var untrusted = TestSupport.network(new FakeResolver(), routeTo(server.port()), TestSupport.untrustingTls());
            var error = assertThrows(PinnedHttp.HttpException.class, () -> get(untrusted, true, "/"));
            assertEquals(PinnedHttp.HttpException.Kind.TLS, error.kind);
            // The JDK's trust store, as production uses, doesn't have the test CA.
            var system = TestSupport.network(new FakeResolver(), routeTo(server.port()), null);
            assertEquals(PinnedHttp.HttpException.Kind.TLS, assertThrows(PinnedHttp.HttpException.class, () -> get(system, true, "/")).kind);
            // A name the certificate isn't for.
            var mismatch = assertThrows(PinnedHttp.HttpException.class, () -> get(net, true, "other.example", "/", TIMEOUTS));
            assertEquals(PinnedHttp.HttpException.Kind.TLS, mismatch.kind);
        }
    }

    /// A server that keeps sending a byte at a time never trips the read timeout, but the
    /// exchange's total does.
    @Test
    void givesUpOnATricklingBodyAtTheDeadline() throws Exception {
        try (var listener = TestSupport.tricklingServer("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n")) {
            var net = TestSupport.network(new FakeResolver(), routeTo(listener.getLocalPort()));
            var timeouts = new PinnedHttp.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(500));
            long started = System.nanoTime();
            var error = assertThrows(PinnedHttp.HttpException.class, () -> {
                try (var response = get(net, false, "www.example.com", "/", timeouts)) {
                    response.readBody(1 << 20);
                }
            });
            assertEquals(PinnedHttp.HttpException.Kind.READ_TIMEOUT, error.kind);
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
        }
    }
}
