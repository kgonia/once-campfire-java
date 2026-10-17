package campfire.useragent;

import java.util.Map;

/// `allow_browser versions: VERSIONS, block: -> { render template: "sessions/incompatible_browser" }`
/// (reference/app/controllers/concerns/allow_browser.rb): Rails' `BrowserBlocker#blocked?` with
/// `VERSIONS = { safari: 17.2, chrome: 120, firefox: 121, opera: 104, ie: false }`.
///
/// A blocked request gets `sessions/incompatible_browser` with status 200, whatever its format.
public final class AllowBrowser {
    private AllowBrowser() {}

    private static final Map<String, Version> MINIMUM_VERSIONS = Map.of(
            "safari", new Version("17.2"),
            "chrome", new Version("120"),
            "firefox", new Version("121"),
            "opera", new Version("104"));

    /// Blocked only when the header is present, the gem reports a version, the browser is
    /// guarded (Safari, Chrome, Firefox, Opera or IE) and too old (any IE), and it isn't a bot.
    /// Where Rails raises (a versioned agent with a nil browser, a 500) this answers false.
    public static boolean blocked(String userAgent) {
        return userAgent != null && Strings.present(userAgent) && Platform.of(userAgent).browserBlocked();
    }

    static boolean tryBlocked(String userAgent, UserAgent agent) {
        if (!Strings.present(userAgent)) return false;
        Version version = agent.tryVersion();
        if (version == null || !version.isPresent()) return false;

        String browser = agent.tryBrowser();
        if (browser == null) throw Raised.INSTANCE;
        // `normalized_browser_name`, looked up in the versions with indifferent access.
        String name = Strings.downcase(browser);
        if (name.equals("internet explorer") || name.equals("ie")) return !agent.isBot();

        Version minimum = MINIMUM_VERSIONS.get(name);
        return minimum != null && version.lessThan(minimum) && !agent.isBot();
    }
}
