package campfire.useragent;

import java.util.concurrent.ConcurrentHashMap;

/// `ApplicationPlatform.new(request.user_agent)` (reference/app/models/application_platform.rb,
/// over platform_agent 1.0.1): the layout's `platform` helper.
///
/// Predicates that raise in Ruby (`user_agent.browser.match?` on a nil browser, which Rails turns
/// into a 500) answer false here; `browser()` and `operatingSystem()` answer `""` for nil.
public final class Platform {
    /// Requests mostly come from a handful of browsers, so parses are kept, up to a bound.
    private static final int CACHE_LIMIT = 2048;
    private static final int CACHEABLE_LENGTH = 1024;
    private static final ConcurrentHashMap<String, Platform> CACHE = new ConcurrentHashMap<>();

    private final boolean ios, android, mac, windows, chrome, firefox, safari, edge, appleMessages, browserBlocked;
    private final String browser, operatingSystem;

    private Platform(String userAgent) {
        UserAgent agent = UserAgent.parse(userAgent);
        String browser = agent.tryBrowser();
        String operatingSystem = null;
        boolean windows = false;
        try {
            operatingSystem = tryOperatingSystem(agent);
            windows = "Windows".equals(operatingSystem);
        } catch (Raised e) {
            // nil.match? or the gem raising: false, and "" for the name
        }
        boolean blocked;
        try {
            blocked = AllowBrowser.tryBlocked(userAgent, agent);
        } catch (Raised e) {
            blocked = false;
        }

        this.ios = userAgent.contains("iPhone") || userAgent.contains("iPad");
        this.android = userAgent.contains("Android");
        this.mac = userAgent.contains("Macintosh");
        this.windows = windows;
        this.chrome = browser != null && browser.contains("Chrome");
        this.firefox = browser != null && (browser.contains("Firefox") || browser.contains("FxiOS"));
        this.safari = browser != null && browser.contains("Safari");
        this.edge = browser != null && browser.contains("Edg");
        this.appleMessages = appleMessages(userAgent);
        this.browserBlocked = blocked;
        this.browser = browser == null ? "" : browser;
        this.operatingSystem = operatingSystem == null ? "" : operatingSystem;
    }

    /// The platform for a User-Agent header; a missing one reads as `""`.
    public static Platform of(String userAgent) {
        String header = userAgent == null ? "" : userAgent;
        if (header.length() > CACHEABLE_LENGTH) return new Platform(header);
        Platform platform = CACHE.get(header);
        if (platform == null) {
            if (CACHE.size() >= CACHE_LIMIT) CACHE.clear();
            platform = new Platform(header);
            CACHE.put(header, platform);
        }
        return platform;
    }

    public boolean ios() {
        return ios;
    }

    public boolean android() {
        return android;
    }

    public boolean mac() {
        return mac;
    }

    /// `operating_system == "Windows"`.
    public boolean windows() {
        return windows;
    }

    public boolean chrome() {
        return chrome;
    }

    /// Firefox, or Firefox for iOS ("FxiOS").
    public boolean firefox() {
        return firefox;
    }

    public boolean safari() {
        return safari;
    }

    /// `browser.match?(/Edg/)`.
    public boolean edge() {
        return edge;
    }

    /// Apple Messages link previews claim to be both the Facebook and Twitter bots; Campfire keeps
    /// them from seeing "Unsupported browser".
    public boolean appleMessages() {
        return appleMessages;
    }

    public boolean mobile() {
        return ios || android;
    }

    public boolean desktop() {
        return !mobile();
    }

    /// The gem's `browser`: "Chrome", "Safari", "Firefox", "Edge", "Opera", or a product name.
    public String browser() {
        return browser;
    }

    /// `browser.capitalize`, as the PWA settings instructions print it.
    public String browserCapitalized() {
        return capitalize(browser);
    }

    /// "Android", "iPad", "iPhone", "macOS", "Windows", "ChromeOS", "Linux", or the gem's `os`.
    public String operatingSystem() {
        return operatingSystem;
    }

    boolean browserBlocked() {
        return browserBlocked;
    }

    /// `operating_system`; null for nil.
    static String tryOperatingSystem(UserAgent agent) {
        String platform = agent.tryPlatform();
        if (platform != null) {
            if (platform.contains("Android")) return "Android";
            if (platform.contains("iPad")) return "iPad";
            if (platform.contains("iPhone")) return "iPhone";
            if (platform.contains("Macintosh")) return "macOS";
            if (platform.contains("Windows")) return "Windows";
            if (platform.contains("CrOS")) return "ChromeOS";
        }
        String os = agent.tryOs();
        return os != null && os.contains("Linux") ? "Linux" : os;
    }

    /// `match?(/facebookexternalhit/i) && match?(/Twitterbot/i)`.
    static boolean appleMessages(String userAgent) {
        String lowercased = Strings.downcase(userAgent);
        return lowercased.contains("facebookexternalhit") && lowercased.contains("twitterbot");
    }

    /// Ruby's `String#capitalize`: the first character titlecased, the rest downcased.
    static String capitalize(String s) {
        if (s.isEmpty()) return s;
        int first = s.codePointAt(0);
        return new StringBuilder(s.length())
                .appendCodePoint(Character.toTitleCase(first))
                .append(Strings.downcase(s.substring(Character.charCount(first))))
                .toString();
    }
}
