package campfire.useragent;

import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// `allow_browser versions: { safari: 17.2, chrome: 120, firefox: 121, opera: 104, ie: false }`
/// (reference/app/controllers/concerns/allow_browser.rb): older versions of those browsers, and
/// any Internet Explorer, get the incompatible-browser page. Bots and agents without a version pass.
@NullMarked
public final class AllowBrowser {
    private AllowBrowser() {}

    private static final Map<String, int[]> MINIMUM_VERSIONS = Map.of(
        "safari", new int[] {17, 2},
        "chrome", new int[] {120},
        "firefox", new int[] {121},
        "opera", new int[] {104});

    public static boolean blocked(@Nullable String userAgent) {
        return userAgent != null && Platform.of(userAgent).browserBlocked();
    }

    static boolean blocks(UserAgent agent) {
        if (agent.bot() || agent.version().isEmpty()) return false;
        String name = agent.browser().toLowerCase(java.util.Locale.ROOT);
        if (name.equals("internet explorer") || name.equals("ie")) return true;
        int[] minimum = MINIMUM_VERSIONS.get(name);
        return minimum != null && compare(agent.version(), minimum) < 0;
    }

    /// Dotted numeric versions, compared by the leading number of each part.
    static int compare(String version, int[] minimum) {
        String[] parts = version.split("\\.");
        for (int i = 0; i < Math.max(parts.length, minimum.length); i++) {
            int a = i < parts.length ? leadingNumber(parts[i]) : 0;
            int b = i < minimum.length ? minimum[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        // A prerelease ("17.2beta") comes before its release, as Gem::Version has it.
        return version.chars().anyMatch(Character::isLetter) ? -1 : 0;
    }

    private static int leadingNumber(String part) {
        int end = 0;
        while (end < part.length() && end < 9 && Character.isDigit(part.charAt(end))) end++;
        return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
    }
}
