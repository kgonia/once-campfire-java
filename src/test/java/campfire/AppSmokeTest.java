package campfire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.rails.Json;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/// The whole app on a fresh install, through Vert.x, every filter and the finisher: unit tests
/// call classes directly and miss what only the running pipeline does.
@QuarkusTest
@TestProfile(AppSmokeTest.FreshInstall.class)
class AppSmokeTest {
    public static class FreshInstall implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                return Map.of("campfire.storage", Files.createTempDirectory("campfire-smoke").toString(),
                    "campfire.secret-key-base", "a".repeat(128), "campfire.disable-ssl", "true");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @TestHTTPResource("/")
    URI base;

    // HTTP/1.1: a form POST over HTTP/2 fails while Front reads bodies with its own Vert.x
    // BodyHandler, which Quarkus REST then tries to pause. Moving form reading to Quarkus REST fixes it.
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(new CookieManager()).build();

    @Test
    void firstRunSignInPostAndRead() throws Exception {
        assertEquals(200, get("/up").statusCode());
        for (String path : new String[] {"/up", "/robots.txt", "/robots.txt"}) {
            assertEquals("SAMEORIGIN", get(path).headers().firstValue("x-frame-options").orElse(null), path);
        }
        assertEquals(base.resolve("/session/new").toString(), location(get("/")));
        assertEquals(base.resolve("/first_run").toString(), location(get("/session/new")));

        var signedUp = post("/first_run", Map.of("user[name]", "Ada", "user[email_address]", "ada@example.com", "user[password]", "correct horse"));
        assertEquals(302, signedUp.statusCode());
        assertTrue(signedUp.headers().allValues("set-cookie").stream().anyMatch(c -> c.startsWith("session_token=")));

        String room = URI.create(location(get("/"))).getPath();
        assertTrue(room.matches("/rooms/\\d+"), room);
        String page = get(room).body();
        assertTrue(page.startsWith("<!DOCTYPE html>") && page.strip().endsWith("</html>"));

        var posted = post(room + "/messages", Map.of("message[body]", "<div>Hello from the smoke test</div>",
            "message[client_message_id]", UUID.randomUUID().toString()));
        assertEquals(200, posted.statusCode(), posted.body());
        assertTrue(get(room).body().contains("Hello from the smoke test"));

        var gzipped = http.send(HttpRequest.newBuilder(base.resolve(room)).header("Accept-Encoding", "gzip").build(),
            HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("gzip", gzipped.headers().firstValue("content-encoding").orElse(null));
        assertTrue(gzipped.headers().firstValue("vary").orElse("").contains("Accept-Encoding"));
        String unzipped = new String(new GZIPInputStream(new ByteArrayInputStream(gzipped.body())).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(unzipped.contains("Hello from the smoke test"));

        String etag = gzipped.headers().firstValue("etag").orElseThrow();
        var revalidated = http.send(HttpRequest.newBuilder(base.resolve(room)).header("If-None-Match", etag).build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(304, revalidated.statusCode());

        blobsServeRangesAndConditionalGets();
    }

    /// A direct upload, then its proxy and disk URLs with ranges and validators.
    private void blobsServeRangesAndConditionalGets() throws Exception {
        byte[] content = "Hello, ranges and validators".getBytes(StandardCharsets.UTF_8);
        String checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(content));
        String params = "{\"blob\":{\"filename\":\"notes.txt\",\"content_type\":\"text/plain\",\"byte_size\":" + content.length + ",\"checksum\":\""
            + checksum + "\"}}";
        var created = http.send(HttpRequest.newBuilder(base.resolve("/rails/active_storage/direct_uploads")).header("Content-Type", "application/json")
            .header("Sec-Fetch-Site", "same-origin").POST(HttpRequest.BodyPublishers.ofString(params)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, created.statusCode(), created.body());
        var blob = (Map<?, ?>) Json.parse(created.body());
        String upload = (String) ((Map<?, ?>) blob.get("direct_upload")).get("url");
        var put = http.send(HttpRequest.newBuilder(URI.create(upload)).header("Content-Type", "text/plain")
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(204, put.statusCode());

        String proxy = "/rails/active_storage/blobs/proxy/" + blob.get("signed_id") + "/notes.txt";
        var partial = getWith(proxy, "Range", "bytes=7-12", "Accept-Encoding", "gzip");
        assertEquals(206, partial.statusCode());
        assertEquals("ranges", new String(partial.body(), StandardCharsets.UTF_8));
        assertEquals("6", partial.headers().firstValue("content-length").orElseThrow());
        assertEquals("bytes 7-12/" + content.length, partial.headers().firstValue("content-range").orElseThrow());
        assertTrue(partial.headers().firstValue("content-encoding").isEmpty(), "a 206 is never gzipped");
        var several = getWith(proxy, "Range", "bytes=0-1,3-4");
        assertEquals(200, several.statusCode());
        assertArrayEquals(content, several.body());
        assertEquals(416, getWith(proxy, "Range", "bytes=999-").statusCode());
        assertEquals(304, getWith(proxy, "If-None-Match", several.headers().firstValue("etag").orElseThrow()).statusCode());

        String disk = location(get("/rails/active_storage/blobs/redirect/" + blob.get("signed_id") + "/notes.txt"));
        var tail = getWith(disk, "Range", "bytes=-5", "Accept-Encoding", "gzip");
        assertEquals(206, tail.statusCode());
        assertEquals("5", tail.headers().firstValue("content-length").orElseThrow());
        assertEquals("ators", new String(tail.body(), StandardCharsets.UTF_8));
        // Before any 200: the front cache keeps those and, like Thruster, only honours If-None-Match.
        assertEquals(304, getWith(disk, "If-Modified-Since", tail.headers().firstValue("last-modified").orElseThrow()).statusCode());
        var whole = getWith(disk, "If-Modified-Since", "not a date");
        assertEquals(200, whole.statusCode());
        assertArrayEquals(content, whole.body());
    }

    private HttpResponse<byte[]> getWith(String path, String... headers) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).headers(headers).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
            .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(base.resolve(path)).header("Content-Type", "application/x-www-form-urlencoded")
            .header("Sec-Fetch-Site", "same-origin").header("Accept", "text/vnd.turbo-stream.html, text/html")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("location").orElseThrow(() -> new AssertionError("no redirect: " + response.statusCode()));
    }
}
