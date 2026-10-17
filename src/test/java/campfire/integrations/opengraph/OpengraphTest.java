package campfire.integrations.opengraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.integrations.TestSupport;
import campfire.integrations.TestSupport.FakeResolver;
import campfire.integrations.TestSupport.FakeServer;
import campfire.integrations.TestSupport.MappingDialer;
import campfire.integrations.TestSupport.Route;
import campfire.integrations.net.Guard;
import campfire.integrations.net.Network;
import campfire.rails.Json;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/// Replays testdata/opengraph_cases.json (fake DNS, a fake server for the fake public
/// addresses) and compares the response, the DNS lookups and the HTTP requests with what the
/// Rails reference did for the same cases (testdata/opengraph_expected.json, recorded by the
/// Rust port's oracle).
class OpengraphTest {
    @SuppressWarnings("unchecked")
    private static Route route(Map<String, Object> spec) {
        var route = new Route((String) spec.get("method"), (String) spec.get("host"), (String) spec.get("path"), ((Number) spec.get("status")).intValue());
        for (var header : (List<List<String>>) spec.get("headers")) route.header(header.get(0), header.get(1));
        byte[] body;
        if (spec.get("body_b64") instanceof String b64) {
            body = Base64.getDecoder().decode(b64);
        } else if (spec.get("body_repeat") instanceof List<?> repeat) {
            body = ((String) repeat.get(0)).repeat(((Number) repeat.get(1)).intValue()).getBytes(StandardCharsets.UTF_8);
        } else {
            body = spec.get("body") instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : new byte[0];
        }
        if (spec.get("pad_to") instanceof Number padTo) {
            int from = body.length;
            body = Arrays.copyOf(body, padTo.intValue());
            Arrays.fill(body, from, body.length, (byte) ' ');
        }
        route.body(body);
        route.chunked = Boolean.TRUE.equals(spec.get("chunked"));
        route.gzip = Boolean.TRUE.equals(spec.get("gzip"));
        return route;
    }

    @Test
    @SuppressWarnings("unchecked")
    void unfurlsLikeTheReference() throws Exception {
        var spec = (Map<String, Object>) TestSupport.json("opengraph_cases.json");
        var expected = (List<Map<String, Object>>) TestSupport.json("opengraph_expected.json");
        var routes = ((List<Map<String, Object>>) spec.get("routes")).stream().map(OpengraphTest::route).toList();
        var publicIps = new HashSet<InetAddress>();
        for (Object ip : (List<Object>) spec.get("public_ips")) publicIps.add(Guard.parseAddress((String) ip));

        var failures = new ArrayList<String>();
        var cases = (List<Map<String, Object>>) spec.get("cases");
        try (var server = FakeServer.start(routes)) {
            for (int i = 0; i < cases.size(); i++) {
                var testCase = cases.get(i);
                var wanted = expected.get(i);
                assertEquals(wanted.get("name"), testCase.get("name"));
                var resolver = new FakeResolver();
                for (var host : ((Map<String, Object>) spec.get("hosts")).entrySet()) {
                    var answers = new ArrayList<List<InetAddress>>();
                    for (var list : (List<List<String>>) host.getValue()) answers.add(TestSupport.ips(list.toArray(String[]::new)));
                    resolver.set(host.getKey(), answers);
                }
                var net = TestSupport.network(resolver, new MappingDialer(publicIps, server.port()));
                int before = server.received.size();

                var response = new LinkedHashMap<String, Object>();
                switch (Opengraph.unfurl(net, (String) testCase.get("url"))) {
                    case Opengraph.Unfurl.Json json -> {
                        response.put("status", 200L);
                        response.put("body", json.body());
                    }
                    case Opengraph.Unfurl.NoContent noContent -> response.put("status", 204L);
                    case Opengraph.Unfurl.Raised raised -> {
                        response.put("status", 500L);
                        response.put("error", raised.rubyClass());
                    }
                }
                var requests = new ArrayList<Object>();
                for (var r : server.received.subList(before, server.received.size())) {
                    requests.add(Arrays.asList(r.method(), r.header("host"), r.target(), r.header("accept"), r.header("accept-encoding"), r.header("user-agent")));
                }
                var actual = new LinkedHashMap<String, Object>();
                actual.put("response", response);
                actual.put("lookups", List.copyOf(resolver.lookups));
                actual.put("requests", requests);
                var want = new LinkedHashMap<String, Object>();
                want.put("response", wanted.get("response"));
                want.put("lookups", wanted.get("lookups"));
                want.put("requests", wanted.get("requests"));
                if (!Json.generate(actual).equals(Json.generate(want))) {
                    failures.add(testCase.get("name") + ":\n  expected " + Json.generate(want) + "\n  actual   " + Json.generate(actual));
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " of " + cases.size() + " cases differ:\n" + String.join("\n", failures));
    }

    /// Probed against the reference (`strip_tags` then `sanitize`) by the Rust port.
    @Test
    void stripsTagsLikeRails() {
        String[][] cases = {{"Tom & Jerry", "Tom &amp; Jerry"}, {"a < b", "a &lt; b"}, {"x&nbsp;y", "x&nbsp;y"}, {" nb", "&nbsp;nb"},
            {"Hey!<script>alert('hi')</script>", "Hey!alert('hi')"}, {"<!-- c -->t", "t"}, {"a &lt;b&gt; c", "a &lt;b&gt; c"},
            {"<p>one</p><p>two</p>", "onetwo"}, {"\"q\" 'a'", "\"q\" 'a'"}, {"<style>x</style>y", "xy"}, {"&amp;amp;", "&amp;amp;"},
            {"<b>bold</b>", "bold"}, {"</script><img src=a onerror=prompt(1)>", ""}, {" sp  ", " sp  "},
            {"<textarea>t<b>x</b></textarea>", "t&lt;b&gt;x&lt;/b&gt;"}};
        for (String[] c : cases) assertEquals(c[1], Metadata.sanitize(Metadata.stripTags(c[0])), c[0]);
    }

    /// test/models/opengraph/document_test.rb
    @Test
    void extractsOpengraphTags() {
        String html = "<html><head><meta property=\"og:url\" content=\"https://example.com\"><meta property=\"og:title\" content=\"Hey!\">"
            + "<meta property=\"og:description\" content=\"desc..\"><meta property=\"og:image\" content=\"https://example.com/image.png\"></head></html>";
        var expected = new LinkedHashMap<String, String>();
        expected.put("title", "Hey!");
        expected.put("url", "https://example.com");
        expected.put("image", "https://example.com/image.png");
        expected.put("description", "desc..");
        assertEquals(expected, Document.opengraphAttributes(html.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, Document.opengraphAttributes(html.replace("property=", "name=").getBytes(StandardCharsets.UTF_8)));
        assertEquals(Map.of(), Document.opengraphAttributes(null));
        assertEquals("café ÿ x", Document.decode(new byte[] {'c', 'a', 'f', (byte) 0xc3, (byte) 0xa9, ' ', (byte) 0xff, ' ', 'x'}));
        assertEquals("\u0093q\u0094", Document.decode(new byte[] {(byte) 0x93, 'q', (byte) 0x94}));
    }

    /// Pages as large as a fetch allows, built to make a parser do quadratic work, parse in time
    /// proportional to their size.
    @Test
    void parsesPathologicalPagesQuickly() {
        int limit = Fetch.MAX_BODY_SIZE;
        var pages = new ArrayList<String>();
        var attributes = new StringBuilder("<meta property=\"og:title\" content=\"x\" ");
        for (int i = 0; attributes.length() < limit - 64; i++) attributes.append(String.format("a%07d ", i));
        pages.add(attributes.append(">").toString());
        var metas = new StringBuilder("<meta charset=utf-8>");
        for (int i = 0; metas.length() < limit - 128; i++) metas.append("<meta property=\"og:t").append(i).append("\" content=\"x\">");
        pages.add(metas.append("<meta property=\"og:title\" content=\"x\">").toString());
        var references = new StringBuilder("<meta property=\"og:title\" content=\"");
        while (references.length() < limit - 64) references.append("&amp;é");
        pages.add(references.append("\">").toString());
        for (String page : pages) {
            long started = System.nanoTime();
            var found = Document.opengraphAttributes(page.getBytes(StandardCharsets.UTF_8));
            assertTrue(found.containsKey("title"));
            long elapsed = System.nanoTime() - started;
            assertTrue(elapsed < Duration.ofSeconds(5).toNanos(), Duration.ofNanos(elapsed) + " for " + page.substring(0, 60));
        }
    }

    private static Network networkTo(int port, String... publicIps) {
        var resolver = new FakeResolver().set("www.example.com", "93.184.216.34").set("example.com", "93.184.216.35");
        return TestSupport.network(resolver, new MappingDialer(Set.copyOf(TestSupport.ips(publicIps)), port));
    }

    /// test/controllers/unfurl_links_controller_test.rb over HTTPS: the pinned address, with the
    /// certificate verified against the host name.
    @Test
    void unfurlsOverHttps() throws Exception {
        String page = "<html><head><meta property=\"og:url\" content=\"https://example.com\"><meta property=\"og:title\" content=\"Hey!\">"
            + "<meta property=\"og:description\" content=\"desc..\"><meta property=\"og:image\" content=\"https://example.com/image.png\"></head></html>";
        try (var server = FakeServer.startTls(List.of(new Route("GET", "www.example.com", "/", 200).header("Content-Type", "text/html").body(page),
            new Route("HEAD", "example.com", "/image.png", 200).header("Content-Type", "image/png")))) {
            var resolver = new FakeResolver().set("www.example.com", "93.184.216.34").set("example.com", "93.184.216.35");
            var dialer = new MappingDialer(Set.copyOf(TestSupport.ips("93.184.216.34", "93.184.216.35")), server.port());
            var net = TestSupport.network(resolver, dialer);
            var json = assertInstanceOf(Opengraph.Unfurl.Json.class, Opengraph.unfurl(net, "https://www.example.com"));
            assertEquals("{\"title\":\"Hey!\",\"url\":\"https://example.com\",\"image\":\"https://example.com/image.png\",\"description\":\"desc..\","
                + "\"context_for_validation\":{\"context\":null},\"errors\":{}}", json.body());
            assertEquals(List.of("93.184.216.34:443", "93.184.216.35:443"),
                dialer.dialed.stream().map(a -> a.getAddress().getHostAddress() + ":" + a.getPort()).toList());

            // A certificate that doesn't verify is a failed fetch.
            assertInstanceOf(Opengraph.Unfurl.NoContent.class, Opengraph.unfurl(net.withTls(TestSupport.untrustingTls()), "https://www.example.com"));
        }
    }

    /// A page followed by a gigabyte of zeros, gzipped to a megabyte, is past the 5MB limit as
    /// soon as that much is inflated. The same page followed by less unfurls.
    @Test
    void stopsReadingAGzipBombAtTheLimit() throws Exception {
        String page = "<meta property=\"og:title\" content=\"Hey!\"><meta property=\"og:url\" content=\"http://www.example.com/\">"
            + "<meta property=\"og:description\" content=\"desc..\">";
        var buffer = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(buffer)) {
            gzip.write(page.getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzipped = buffer.toByteArray();
        var routes = List.of(
            new Route("GET", "*", "/", 200).header("Content-Type", "text/html").header("Content-Encoding", "gzip")
                .body(concat(gzipped, TestSupport.gzipBomb(1024))),
            new Route("GET", "*", "/small", 200).header("Content-Type", "text/html").header("Content-Encoding", "gzip")
                .body(concat(gzipped, TestSupport.gzipBomb(2))));
        try (var server = FakeServer.start(routes)) {
            var net = networkTo(server.port(), "93.184.216.34");
            assertInstanceOf(Opengraph.Unfurl.Json.class, Opengraph.unfurl(net, "http://www.example.com/small"));
            long started = System.nanoTime();
            assertInstanceOf(Opengraph.Unfurl.NoContent.class, Opengraph.unfurl(net, "http://www.example.com/"));
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(3).toNanos());
        }
    }

    /// A server that keeps sending a byte at a time never trips a read timeout, but the unfurl
    /// as a whole gives up.
    @Test
    void givesUpOnATricklingPage() throws Exception {
        try (var listener = TestSupport.tricklingServer("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n")) {
            long started = System.nanoTime();
            var deadline = Duration.ofMillis(500);
            assertInstanceOf(Opengraph.Unfurl.NoContent.class,
                Opengraph.unfurl(networkTo(listener.getLocalPort(), "93.184.216.34"), "http://www.example.com/", deadline));
            assertTrue(System.nanoTime() - started < deadline.multipliedBy(2).toNanos());
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
