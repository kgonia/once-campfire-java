package campfire.useragent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The layout's `platform` helper (`ApplicationPlatform` in Rails): which browser and system the
/// PWA and notification help pages describe, and whether the incompatible-browser page applies.
@NullMarked
public final class Platform {
    /// Requests come from a handful of browsers, so each header is read once.
    private static final Cache<String, Platform> CACHE = Caffeine.newBuilder().maximumSize(2048).build();
    private static final int CACHEABLE_LENGTH = 1024;

    private final String userAgent;
    private final UserAgent agent;
    private final boolean browserBlocked;

    private Platform(String userAgent) {
        this.userAgent = userAgent;
        this.agent = UserAgent.parse(userAgent);
        this.browserBlocked = !userAgent.isBlank() && AllowBrowser.blocks(agent);
    }

    /// The platform for a User-Agent header; a missing one reads as `""`.
    public static Platform of(@Nullable String userAgent) {
        String header = userAgent == null ? "" : userAgent;
        return header.length() > CACHEABLE_LENGTH ? new Platform(header) : CACHE.get(header, Platform::new);
    }

    public boolean ios() {
        return userAgent.contains("iPhone") || userAgent.contains("iPad");
    }

    public boolean android() {
        return userAgent.contains("Android");
    }

    public boolean mac() {
        return userAgent.contains("Macintosh");
    }

    public boolean windows() {
        return operatingSystem().equals("Windows");
    }

    public boolean chrome() {
        return agent.browser().contains("Chrome");
    }

    /// Firefox, or Firefox for iOS ("FxiOS").
    public boolean firefox() {
        return agent.browser().contains("Firefox") || agent.browser().contains("FxiOS");
    }

    public boolean safari() {
        return agent.browser().contains("Safari");
    }

    /// EdgeHTML only: Chromium Edge reads as Chrome.
    public boolean edge() {
        return agent.browser().contains("Edg");
    }

    /// Apple Messages link previews claim to be both the Facebook and Twitter bots; Campfire keeps
    /// them from seeing "Unsupported browser".
    public boolean appleMessages() {
        String lowercased = userAgent.toLowerCase(Locale.ROOT);
        return lowercased.contains("facebookexternalhit") && lowercased.contains("twitterbot");
    }

    public boolean mobile() {
        return ios() || android();
    }

    public boolean desktop() {
        return !mobile();
    }

    /// "Chrome", "Safari", "Firefox", "Edge", "Opera", or the agent's product name.
    public String browser() {
        return agent.browser();
    }

    /// "Android", "iPad", "iPhone", "macOS", "Windows", "ChromeOS", "Linux", or the agent's system.
    public String operatingSystem() {
        String platform = agent.platform();
        if (platform.contains("Android")) return "Android";
        if (platform.contains("iPad")) return "iPad";
        if (platform.contains("iPhone")) return "iPhone";
        if (platform.contains("Macintosh")) return "macOS";
        if (platform.contains("Windows")) return "Windows";
        if (platform.contains("CrOS")) return "ChromeOS";
        return agent.os().contains("Linux") ? "Linux" : agent.os();
    }

    boolean browserBlocked() {
        return browserBlocked;
    }
}
