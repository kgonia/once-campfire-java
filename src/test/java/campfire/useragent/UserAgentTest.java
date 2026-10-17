package campfire.useragent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// Against what the Rails reference app answered (`vectors/campfire_user_agents.json`), for
/// everything Campfire shows: the layout's platform helper, the incompatible-browser decision and
/// the push subscriptions line. Agents nobody runs Campfire in (media players, consoles, podcast
/// apps, WeChat, pre-2008 Safari) read as their first product instead of the gem's special cases.
class UserAgentTest {
    /// The vector agents whose gem readings this class deliberately doesn't copy. Safari 1 to 5 sent
    /// only a WebKit build, which the gem maps to a version and blocks; read without a version they
    /// skip the incompatible-browser page, and can't run Campfire either way. The gem's Unicode case
    /// folding also reads a Kelvin sign (U+212A) as `k`, so a fuzzed `AppleWeb<U+212A>it` is WebKit there.
    /// Case is ignored for ASCII only, or the Kelvin sign would match every `applewebkit`.
    static final Pattern NOT_BROWSERS = Pattern.compile(Stream.of("NSPlayer", "Windows-Media-Player", "WMFSDK", "iTunes",
            "AppleCoreMedia", "PlayStation", "Podcast Addict", "MicroMessenger", "Lavf", "Opera Mini",
            "Safari/125.8", "Safari/419.3", "Safari/534.52.7", "applewebkit/85.7", "AppleWeb\u212Ait")
        .map(Pattern::quote).collect(Collectors.joining("|")), Pattern.CASE_INSENSITIVE);

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> vectors() {
        try (var in = UserAgentTest.class.getResourceAsStream("/vectors/campfire_user_agents.json")) {
            var all = (Map<String, Object>) Json.decodeValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
            return (List<Map<String, Object>>) all.get("user_agents");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void readsBrowsersAsRailsDoes() {
        var failures = new ArrayList<String>();
        int compared = 0;
        for (var c : vectors()) {
            String ua = c.get("ua") instanceof String s ? s : "";
            if (NOT_BROWSERS.matcher(ua).find()) continue;
            compared++;
            Platform platform = Platform.of(ua);
            UserAgent agent = UserAgent.parse(ua);
            String label = Json.encode(ua);
            if (c.get("application_platform") instanceof Map<?, ?> expected) {
                var actual = Map.of("ios", platform.ios(), "android", platform.android(), "mac", platform.mac(), "chrome", platform.chrome(),
                    "firefox", platform.firefox(), "safari", platform.safari(), "edge", platform.edge(),
                    "apple_messages", platform.appleMessages(), "windows", platform.windows(), "browser", platform.browser());
                actual.forEach((key, value) -> {
                    Object want = expected.get(key);
                    // Where Ruby raised (a nil browser), Campfire answered false and "".
                    if (want instanceof Map<?, ?>) want = value instanceof Boolean ? false : "";
                    if (want == null) want = "";
                    if (!Objects.equals(want, value)) failures.add(label + " " + key + ": expected " + want + ", got " + value);
                });
                Object os = expected.get("operating_system");
                String wantOs = os instanceof String s ? s : "";
                if (!wantOs.equals(platform.operatingSystem())) failures.add(label + " operating_system: expected " + wantOs + ", got " + platform.operatingSystem());
            }
            boolean blocked = Boolean.TRUE.equals(c.get("blocked"));
            if (blocked != AllowBrowser.blocked(ua)) failures.add(label + " blocked: expected " + blocked);
            String line = text(c.get("browser")) + " " + text(c.get("version")) + " on " + text(c.get("platform"));
            String ours = agent.browser() + " " + agent.version() + " on " + agent.platform();
            if (!line.equals(ours)) failures.add(label + " push line: expected '" + line + "', got '" + ours + "'");
        }
        assertTrue(compared > 300, "compared " + compared);
        assertTrue(failures.isEmpty(), failures.size() + " mismatches:\n" + String.join("\n", failures));
    }

    /// Every agent, browser or not, reads without throwing, and so does every prefix and suffix.
    @Test
    void readsAnything() {
        for (var c : vectors()) {
            String ua = c.get("ua") instanceof String s ? s : "";
            for (int i = 0; i <= ua.length(); i++) {
                for (String part : new String[] {ua.substring(0, i), ua.substring(i)}) {
                    UserAgent.parse(part);
                    Platform.of(part).operatingSystem();
                    AllowBrowser.blocked(part);
                }
            }
        }
    }

    @Test
    void blocksIeAndOldGuardedBrowsersButNotBotsOrUnknowns() {
        assertTrue(AllowBrowser.blocked("Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko"));
        assertTrue(AllowBrowser.blocked("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Safari/605.1.15"));
        assertFalse(AllowBrowser.blocked("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15"));
        assertTrue(AllowBrowser.blocked("Firefox/90"));
        assertFalse(AllowBrowser.blocked("Firefox/121"));
        assertTrue(AllowBrowser.blocked("IE/12"));
        assertFalse(AllowBrowser.blocked("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"));
        assertFalse(AllowBrowser.blocked("curl/8.4.0"));
        assertFalse(AllowBrowser.blocked(" "));
        assertFalse(AllowBrowser.blocked(null));
    }

    @Test
    void pushSubscriptionLine() {
        var agent = UserAgent.parse("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15");
        assertEquals("Safari 17.4 on Macintosh", agent.browser() + " " + agent.version() + " on " + agent.platform());
        assertEquals("OS X 10.15.7", agent.os());
    }

    private static String text(Object value) {
        return value instanceof String s ? s : "";
    }
}
