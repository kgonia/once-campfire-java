package campfire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
