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
        assertArrayEquals(new byte[] {127, 0, 0, 1}, Guard.inetAton("127.1"));
        assertArrayEquals(new byte[] {127, 0, 0, 1}, Guard.inetAton("0x7f.1"));
        assertArrayEquals(new byte[] {127, 0, 0, 1}, Guard.inetAton("2130706433"));
        assertArrayEquals(new byte[] {127, 0, 0, 1}, Guard.inetAton("0177.0.0.01"));
        assertArrayEquals(new byte[] {10, 0, 1, 2}, Guard.inetAton("10.0.258"));
        for (String bad : List.of("09.1.1.1", "256.1.1.1", "1.2.3.4.", "www.example.com")) assertNull(Guard.inetAton(bad), bad);
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
    void parsesUrisLikeRuby() throws Exception {
        var uri = RubyUri.parse("https://x.com/dhh/status/1?s=20");
        assertEquals("x.com", uri.host());
        assertEquals("s=20", uri.query());
        assertEquals(443L, uri.port());
        assertThrows(RubyUri.InvalidUriException.class, () -> RubyUri.parse("http://exa mple.com/ "));
        assertNull(RubyUri.parse("https:/rooms/1").host());
        assertEquals("rooms/1", RubyUri.parse("https:rooms/1").opaque());
        assertEquals("", RubyUri.parse("https://").host());
        assertEquals("[::1]", RubyUri.parse("http://[::1]/x").host());
        assertTrue(assertThrows(RubyUri.InvalidUriException.class, () -> RubyUri.parse("mailto:foo")).invalidComponent());
        RubyUri.parse("mailto:a@b.com");
        assertEquals("https://x.com/a", RubyUri.parse("https://x.com:443/a").toString());
        assertEquals("https", RubyUri.parse("HTTPS://x.com/a").scheme());
        assertEquals("http://X.com/a", RubyUri.parse("HTTP://X.com:80/a").toString());
        assertEquals("file:///etc", RubyUri.parse("FILE:///etc").toString());
        assertEquals("mailto:a@b.com", RubyUri.parse("MailTo:a@b.com").toString());
        assertEquals("/query?a=1&b=2", RubyUri.parse("http://h/query?a=1&b=2#frag").requestUri());
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
