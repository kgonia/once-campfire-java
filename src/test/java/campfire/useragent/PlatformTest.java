package campfire.useragent;

import static campfire.useragent.UserAgentTest.assertNoFailures;
import static campfire.useragent.UserAgentTest.cases;
import static campfire.useragent.UserAgentTest.check;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.rails.Json;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/// `ApplicationPlatform` and `BrowserBlocker` against the Rails vectors.
class PlatformTest {
    /// `user_agent.browser.match?(...)`, which raises for a nil browser.
    static boolean browserMatches(UserAgent agent, String... names) {
        String browser = agent.tryBrowser();
        if (browser == null) throw Raised.INSTANCE;
        for (String name : names) {
            if (browser.contains(name)) return true;
        }
        return false;
    }

    @Test
    void matchesApplicationPlatformAndAllowBrowser() {
        var failures = new ArrayList<String>();
        for (var c : cases("user_agents")) {
            String ua = (String) c.get("ua");
            String header = ua == null ? "" : ua;
            UserAgent agent = UserAgent.parse(ua);
            Platform platform = Platform.of(ua);
            @SuppressWarnings("unchecked")
            var expected = (Map<String, Object>) c.get("application_platform");
            String label = Json.generate(ua);

            Map<String, Supplier<Object>> ruby = Map.ofEntries(
                    Map.entry("ios", platform::ios),
                    Map.entry("android", platform::android),
                    Map.entry("mac", platform::mac),
                    Map.entry("chrome", () -> browserMatches(agent, "Chrome")),
                    Map.entry("firefox", () -> browserMatches(agent, "Firefox", "FxiOS")),
                    Map.entry("safari", () -> browserMatches(agent, "Safari")),
                    Map.entry("edge", () -> browserMatches(agent, "Edg")),
                    Map.entry("apple_messages", platform::appleMessages),
                    Map.entry("mobile", platform::mobile),
                    Map.entry("desktop", platform::desktop),
                    Map.entry("windows", () -> "Windows".equals(Platform.tryOperatingSystem(agent))),
                    Map.entry("operating_system", () -> Platform.tryOperatingSystem(agent)),
                    Map.entry("browser", agent::tryBrowser));
            Map<String, Supplier<Object>> view = Map.ofEntries(
                    Map.entry("chrome", platform::chrome),
                    Map.entry("firefox", platform::firefox),
                    Map.entry("safari", platform::safari),
                    Map.entry("edge", platform::edge),
                    Map.entry("windows", platform::windows),
                    Map.entry("operating_system", platform::operatingSystem),
                    Map.entry("browser", platform::browser));

            for (var field : expected.entrySet()) {
                String name = field.getKey();
                check(failures, label + " " + name, field.getValue(), ruby.get(name));
                // The public accessors answer false, or "", where Ruby raises or answers nil.
                if (view.containsKey(name)) {
                    Object actual = view.get(name).get();
                    boolean nil = field.getValue() == null || field.getValue() instanceof Map;
                    Object want = nil ? (actual instanceof String ? "" : false) : field.getValue();
                    check(failures, label + " " + name + "()", want, () -> actual);
                }
            }

            Object blocked = c.get("blocked");
            check(failures, label + " blocked", blocked, () -> AllowBrowser.tryBlocked(header, agent));
            check(failures, label + " blocked()", blocked instanceof Boolean ? blocked : false, () -> AllowBrowser.blocked(ua));
        }
        assertNoFailures(failures);
    }

    @Test
    void nilBrowserAndOsReadAsEmpty() {
        var platform = Platform.of("curl/8.4.0");
        assertEquals("", platform.operatingSystem());
        assertEquals("curl", platform.browser());
        assertEquals("Curl", platform.browserCapitalized());
        assertTrue(platform.desktop());
        assertFalse(AllowBrowser.blocked(null));
    }

    @Test
    void capitalizesLikeRuby() {
        assertEquals("", Platform.capitalize(""));
        assertEquals("Chrome", Platform.capitalize("chrome"));
        assertEquals("Internet explorer", Platform.capitalize("Internet Explorer"));
        assertEquals("Itunes", Platform.capitalize("iTunes"));
        assertEquals("Ps3 internet browser", Platform.capitalize("PS3 Internet Browser"));
    }

    @Test
    void cachesParses() {
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0";
        assertSame(Platform.of(ua), Platform.of(ua));
        assertTrue(Platform.of(ua).firefox());
        assertTrue(Platform.of(ua).windows());
    }

    @Test
    void blocksIeAndOldGuardedBrowsersButNotBotsOrUnknowns() {
        assertTrue(AllowBrowser.blocked("Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko"));
        assertTrue(AllowBrowser.blocked("Firefox/90"));
        assertFalse(AllowBrowser.blocked("Firefox/121"));
        assertTrue(AllowBrowser.blocked("IE/12"));
        assertFalse(AllowBrowser.blocked("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"));
        assertFalse(AllowBrowser.blocked("curl/8.4.0"));
        assertFalse(AllowBrowser.blocked(" "));
    }
}
