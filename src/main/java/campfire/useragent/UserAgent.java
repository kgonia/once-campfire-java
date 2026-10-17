package campfire.useragent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/// The useragent gem (0.16.11), which Rails' `allow_browser`, platform_agent and the push
/// subscriptions list use to read a User-Agent: `UserAgent.parse` splits the header into products,
/// and the first of `UserAgent::Browsers::ALL` whose `extend?` accepts them decides how `browser`,
/// `version`, `platform` and `os` are answered.
///
/// The public accessors answer `""` where the gem answers nil (as ERB prints it) or raises. `^`
/// and `$` in the gem's regexps are treated as string anchors: a header can't hold a newline.
public final class UserAgent {
    private static final String DEFAULT = "Mozilla/4.0 (compatible)";

    /// One product: `Name/version (comment; parts)`. `lower` is the downcased name, which is what
    /// the gem's case-insensitive `detect_product` compares.
    record Product(String name, String lower, Version version, List<String> comment) {
        String commentAt(int index) {
            return comment != null && index < comment.size() ? comment.get(index) : null;
        }

        String joinedComment() {
            return comment == null ? null : String.join("; ", comment);
        }
    }

    /// `UserAgent::Browsers::ALL` in detection order, then `Base`, which accepts anything.
    enum Kind {
        EDGE, INTERNET_EXPLORER, OPERA, WECHAT_BROWSER, VIVALDI, CHROME, ITUNES, PLAYSTATION, PODCAST_ADDICT,
        WEBKIT, GECKO, WINDOWS_MEDIA_PLAYER, APPLE_CORE_MEDIA, LIBAVFORMAT, BASE
    }

    private final Kind kind;
    private final List<Product> products;
    private final Product application;

    private UserAgent(List<Product> products) {
        this.products = products;
        this.kind = detectKind(products);
        this.application = switch (kind) {
            case CHROME, VIVALDI, WEBKIT, ITUNES, APPLE_CORE_MEDIA -> firstWithComment(products);
            default -> products.isEmpty() ? null : products.getFirst();
        };
    }

    /// `UserAgent.parse`: nil and blank strings parse as "Mozilla/4.0 (compatible)".
    public static UserAgent parse(String userAgent) {
        String s = userAgent == null || stripsToEmpty(userAgent) ? DEFAULT : userAgent;
        int stripped = s.length();
        while (stripped > 0 && Strings.isStripped(s.charAt(stripped - 1))) stripped--;

        var products = new ArrayList<Product>(8);
        // The gem matches the header as given, then each `rest.strip` after a product.
        int pos = 0, limit = s.length();
        while (pos < limit && (pos = matchProduct(s, pos, limit, products)) >= 0) {
            limit = stripped;
            while (pos < limit && Strings.isStripped(s.charAt(pos))) pos++;
        }
        return new UserAgent(List.copyOf(products));
    }

    /// `browser`.
    public String browser() {
        return orEmpty(tryBrowser());
    }

    /// `version.to_s`.
    public String version() {
        try {
            Version version = tryVersion();
            return version == null ? "" : version.toString();
        } catch (Raised e) {
            return "";
        }
    }

    /// `platform`.
    public String platform() {
        try {
            return orEmpty(tryPlatform());
        } catch (Raised e) {
            return "";
        }
    }

    /// `os`.
    public String os() {
        try {
            return orEmpty(tryOs());
        } catch (Raised e) {
            return "";
        }
    }

    @Override
    public String toString() {
        return "UserAgent[" + kind + ", " + products + "]";
    }

    // --- Parsing ---------------------------------------------------------------------------------

    private static boolean stripsToEmpty(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Strings.isStripped(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isProductChar(char c) {
        return c != '/' && !Strings.isRubySpace(c);
    }

    /// `UserAgent::MATCHER` (`^['"]*([^/\s]+)/?([^\s,]*)(\s\(([^\)]*)\)|,gzip\(gfe\))?`) applied to
    /// `s[pos, limit)`: adds the product and answers where the match ends, or -1.
    private static int matchProduct(String s, int pos, int limit, List<Product> products) {
        int quotes = pos;
        while (quotes < limit && (s.charAt(quotes) == '\'' || s.charAt(quotes) == '"')) quotes++;

        int start;
        if (quotes < limit && isProductChar(s.charAt(quotes))) {
            start = quotes;
        } else if (quotes > pos) {
            start = quotes - 1; // backtrack: the last quote is the product
        } else {
            return -1;
        }

        int i = start + 1;
        while (i < limit && isProductChar(s.charAt(i))) i++;
        String name = s.substring(start, i);

        if (i < limit && s.charAt(i) == '/') i++;
        int versionStart = i;
        while (i < limit && !Strings.isRubySpace(s.charAt(i)) && s.charAt(i) != ',') i++;
        String version = s.substring(versionStart, i);

        List<String> comment = null;
        if (i + 1 < limit && Strings.isRubySpace(s.charAt(i)) && s.charAt(i + 1) == '(') {
            int close = s.indexOf(')', i + 2);
            if (close >= 0 && close < limit) {
                comment = List.copyOf(Strings.split(s.substring(i + 2, close), "; "));
                i = close + 1;
            }
        } else if (s.startsWith(",gzip(gfe)", i)) {
            i += ",gzip(gfe)".length();
        }

        products.add(new Product(name, Strings.downcase(name), new Version(version), comment));
        return i;
    }

    // --- Browser detection -------------------------------------------------------------------------

    private static Kind detectKind(List<Product> products) {
        for (Kind kind : Kind.values()) {
            if (extend(kind, products)) return kind;
        }
        throw new AssertionError();
    }

    /// Each browser class's `self.extend?(agent)`.
    private static boolean extend(Kind kind, List<Product> products) {
        Product first = products.isEmpty() ? null : products.getFirst();
        Product last = products.isEmpty() ? null : products.getLast();
        return switch (kind) {
            case BASE -> true;
            case EDGE -> last != null && last.name.equals("Edge");
            case INTERNET_EXPLORER -> first != null && first.comment != null
                    && (contains(first.commentAt(1), "MSIE") || tridentRv(first.joinedComment()));
            case OPERA -> (first != null && first.name.equals("Opera")) || (last != null && last.name.equals("OPR"));
            case WECHAT_BROWSER -> products.stream().anyMatch(p -> p.lower.contains("micromessenger"));
            case VIVALDI -> hasProduct(products, "Vivaldi");
            case CHROME -> hasProduct(products, "Chrome") || hasProduct(products, "CriOS");
            case ITUNES -> hasProduct(products, "iTunes");
            case PLAYSTATION -> {
                String c = first == null ? null : first.commentAt(0);
                yield c != null && (c.contains("PLAYSTATION 3") || c.contains("PlayStation Vita") || c.contains("PlayStation 4"));
            }
            case PODCAST_ADDICT -> products.size() >= 3 && products.get(0).name.equals("Podcast")
                    && products.get(1).name.equals("Addict") && products.get(2).name.equals("-");
            case WEBKIT -> products.stream().anyMatch(p -> p.lower.equals("applewebkit")
                    || (p.comment != null && p.comment.stream().anyMatch(c -> webkitCommentVersion(c) != null)));
            case GECKO -> first != null && first.name.equals("Mozilla");
            case WINDOWS_MEDIA_PLAYER -> {
                String v = first == null ? "" : first.version.toString();
                boolean excluded = v.equals("4.1.0.3856") || v.equals("7.10.0.3059") || v.equals("7.0.0.1956");
                yield !excluded && products.stream().anyMatch(p ->
                        p.name.equals("NSPlayer") || p.name.equals("Windows-Media-Player") || p.name.equals("WMFSDK"));
            }
            case APPLE_CORE_MEDIA -> hasProduct(products, "AppleCoreMedia");
            case LIBAVFORMAT -> products.stream().anyMatch(p ->
                    p.name.equals("Lavf") || (p.name.equals("NSPlayer") && first.version.toString().equals("4.1.0.3856")));
        };
    }

    private static boolean hasProduct(List<Product> products, String name) {
        for (Product p : products) {
            if (p.name.equals(name)) return true;
        }
        return false;
    }

    private static Product firstWithComment(List<Product> products) {
        for (Product p : products) {
            if (p.comment != null && !p.comment.isEmpty()) return p;
        }
        return null;
    }

    // --- Base helpers ------------------------------------------------------------------------------

    private Product first() {
        return products.isEmpty() ? null : products.getFirst();
    }

    private Product last() {
        return products.isEmpty() ? null : products.getLast();
    }

    /// `detect_product`: the first product named `lower`, ignoring case.
    private Product detect(String lower) {
        for (Product p : products) {
            if (p.lower.equals(lower)) return p;
        }
        return null;
    }

    private List<String> applicationComment() {
        return application == null ? null : application.comment;
    }

    private Version baseVersion() {
        return application == null ? null : application.version;
    }

    private static Product required(Product product) {
        if (product == null) throw Raised.INSTANCE;
        return product;
    }

    // --- browser -----------------------------------------------------------------------------------

    /// `browser`; nil for an unparseable string or a bare PlayStation Vita.
    String tryBrowser() {
        return switch (kind) {
            case BASE -> application == null ? null : application.name;
            case EDGE -> "Edge";
            case INTERNET_EXPLORER -> "Internet Explorer";
            case OPERA -> "Opera";
            case WECHAT_BROWSER -> "Wechat Browser";
            case VIVALDI -> "Vivaldi";
            case CHROME -> detect("iron") != null ? "Iron" : "Chrome";
            case ITUNES -> "iTunes";
            case PLAYSTATION -> playstationBrowser();
            case PODCAST_ADDICT -> "Podcast Addict";
            case WEBKIT -> webkitBrowser();
            case GECKO -> geckoBrowser();
            case WINDOWS_MEDIA_PLAYER -> "Windows Media Player";
            case APPLE_CORE_MEDIA -> "AppleCoreMedia";
            case LIBAVFORMAT -> "libavformat";
        };
    }

    private String playstationBrowser() {
        String first = firstOf(applicationComment());
        if (first == null) return null;
        if (first.contains("PLAYSTATION 3")) return "PS3 Internet Browser";
        if (last().name.equals("Silk")) return "Silk";
        if (first.contains("PlayStation 4")) return "PS4 Internet Browser";
        return null;
    }

    private String webkitBrowser() {
        if (contains(webkitOs(), "Android")) return "Android";
        if ("BlackBerry".equals(webkitPlatform())) return "BlackBerry";
        return "Safari";
    }

    private static final String[] GECKO_BROWSERS = {"PaleMoon", "Firefox", "Camino", "Iceweasel", "Seamonkey"};

    private String geckoBrowser() {
        for (String name : GECKO_BROWSERS) {
            if (detect(Strings.downcase(name)) != null) return name;
        }
        return first() == null ? "" : first().name;
    }

    // --- version -----------------------------------------------------------------------------------

    /// `version`; null for nil.
    Version tryVersion() {
        return switch (kind) {
            case BASE, WINDOWS_MEDIA_PLAYER, APPLE_CORE_MEDIA -> baseVersion();
            case EDGE, VIVALDI -> last() == null ? null : last().version;
            case INTERNET_EXPLORER -> {
                String joined = application == null ? null : application.joinedComment();
                yield new Version(orEmpty(group(IE_VERSION, orEmpty(joined), 1)));
            }
            case OPERA -> operaVersion();
            case WECHAT_BROWSER -> required(detect("micromessenger")).version;
            case CHROME -> required(detect("crios") != null ? detect("crios") : detect("chrome")).version;
            case ITUNES -> required(detect("itunes")).version;
            case PLAYSTATION -> playstationVersion();
            case PODCAST_ADDICT -> null;
            case WEBKIT -> webkitVersion();
            case GECKO -> {
                Version version = required(detect(Strings.downcase(geckoBrowser()))).version;
                yield version.isNil() ? baseVersion() : version;
            }
            case LIBAVFORMAT -> detect("nsplayer") != null ? null : baseVersion();
        };
    }

    /// `/Opera Mini/ === application` matches against `UserAgent#to_str`; only the comment can
    /// hold a space.
    private boolean operaMini() {
        return first() != null && contains(first().joinedComment(), "Opera Mini");
    }

    private Version operaVersion() {
        if (operaMini()) {
            // `rescue Version.new` covers a comment without an "Opera Mini/<version>".
            for (String c : applicationComment()) {
                if (c.contains("Opera Mini")) return new Version(orEmpty(Strings.versionAfter(c, "Opera Mini/")));
            }
            return Version.NIL;
        }
        if (detect("version") != null) return detect("version").version;
        if (detect("opr") != null) return detect("opr").version;
        return baseVersion();
    }

    private Version playstationVersion() {
        String os = playstationOs();
        if (os == null) return null;
        if ("Silk".equals(playstationBrowser())) return last().version;
        String platform = playstationPlatform();
        if (platform == null) return null;
        String marker = switch (platform) {
            case "PlayStation 3" -> "PLAYSTATION 3 ";
            case "PlayStation 4" -> "PlayStation 4 ";
            default -> "PlayStation Vita ";
        };
        List<String> parts = Strings.split(os, marker);
        return new Version(parts.isEmpty() ? "" : parts.getLast());
    }

    private Version webkitVersion() {
        Product version = detect("version");
        if (version != null) return version.version;
        String os = webkitOs();
        String ios = os == null ? null : Strings.versionAfter(os, "iOS ");
        if (ios != null && webkitBrowser().equals("Safari")) return new Version(ios.replace('_', '.'));
        Version webkit = webkit();
        return new Version(orEmpty(webkitBuildVersion(webkit == null ? "" : webkit.toString())));
    }

    /// `Webkit#webkit.version`: the AppleWebKit product's version, or one from a comment.
    private Version webkit() {
        Product product = detect("applewebkit");
        if (product != null) return product.version;
        for (Product p : products) {
            if (p.comment == null) continue;
            for (String c : p.comment) {
                String version = webkitCommentVersion(c);
                if (version != null) return new Version(version);
            }
        }
        return null;
    }

    // --- platform ----------------------------------------------------------------------------------

    /// `platform`; null for nil.
    String tryPlatform() {
        List<String> comment = applicationComment();
        String first = firstOf(comment);
        return switch (kind) {
            case BASE, LIBAVFORMAT -> null;
            case EDGE, INTERNET_EXPLORER, WINDOWS_MEDIA_PLAYER -> "Windows";
            case OPERA, APPLE_CORE_MEDIA -> contains(first, "Windows") ? "Windows" : first;
            case WECHAT_BROWSER -> {
                if (contains(first, "iPhone")) yield "iPhone";
                yield anyContains(comment, "Android") ? "Android" : first;
            }
            case CHROME, VIVALDI -> {
                if (contains(first, "Windows")) yield "Windows";
                if (anyContains(comment, "CrOS")) yield "ChromeOS";
                yield anyContains(comment, "Android") ? "Android" : first;
            }
            case WEBKIT, ITUNES -> webkitPlatform();
            case PLAYSTATION -> playstationPlatform();
            case PODCAST_ADDICT -> {
                String os = podcastAddictOs();
                if (os == null) throw Raised.INSTANCE;
                yield os.contains("Android") ? "Android" : null;
            }
            case GECKO -> {
                if (first == null || first.equals("compatible") || first.equals("Mobile")) yield null;
                yield first.startsWith("Windows ") ? "Windows" : first;
            }
        };
    }

    private String webkitPlatform() {
        List<String> comment = applicationComment();
        String first = firstOf(comment);
        if (contains(first, "Windows")) return "Windows";
        if ("BB10".equals(first)) return "BlackBerry";
        if (anyContains(comment, "Android")) return "Android";
        return first;
    }

    private String playstationPlatform() {
        String os = playstationOs();
        if (os == null) return null;
        if (os.contains("PLAYSTATION 3")) return "PlayStation 3";
        if (os.contains("PlayStation 4")) return "PlayStation 4";
        if (os.contains("PlayStation Vita")) return "PlayStation Vita";
        return null;
    }

    // --- os ----------------------------------------------------------------------------------------

    /// `os`; null for nil.
    String tryOs() {
        return switch (kind) {
            case BASE, LIBAVFORMAT -> null;
            case EDGE -> {
                String matched = products.stream()
                        .filter(p -> p.comment != null)
                        .flatMap(p -> p.comment.stream())
                        .map(c -> group(WINDOWS_OS, c, 0))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("");
                yield normalizeOs(matched);
            }
            case INTERNET_EXPLORER -> {
                String joined = application == null ? null : application.joinedComment();
                yield normalizeOs(orEmpty(group(WINDOWS_OS, orEmpty(joined), 0)));
            }
            case OPERA -> {
                List<String> comment = applicationComment();
                if (comment == null) yield null;
                String first = firstOf(comment);
                yield contains(first, "Windows") ? normalizeOs(first) : at(comment, 1);
            }
            case WECHAT_BROWSER, CHROME, VIVALDI, APPLE_CORE_MEDIA -> chromeOs(applicationComment());
            case WEBKIT -> webkitOs();
            case ITUNES -> itunesOs();
            case PLAYSTATION -> playstationOs();
            case PODCAST_ADDICT -> podcastAddictOs();
            case GECKO -> geckoOs();
            case WINDOWS_MEDIA_PLAYER -> windowsMediaPlayerOs();
        };
    }

    /// `os` shared by Chrome, Vivaldi, WechatBrowser and AppleCoreMedia.
    private static String chromeOs(List<String> comment) {
        if (comment == null) return null;
        String pick;
        if (contains(at(comment, 0), "Windows NT")) {
            pick = at(comment, 0);
        } else if (at(comment, 2) == null || contains(at(comment, 1), "Android")) {
            pick = at(comment, 1);
        } else {
            pick = at(comment, 2);
        }
        return pick == null ? null : normalizeOs(pick);
    }

    private String webkitOs() {
        List<String> comment = applicationComment();
        if (comment == null) return null;
        if (contains(at(comment, 0), "Windows NT")) return normalizeOs(at(comment, 0));
        if (at(comment, 2) == null || contains(at(comment, 1), "Android")) {
            return at(comment, 1) == null ? null : normalizeOs(at(comment, 1));
        }
        for (String c : comment) {
            if (IOS_VERSION.matcher(c).find()) return normalizeOs(c);
        }
        return normalizeOs(at(comment, 2));
    }

    private String itunesOs() {
        if (!contains(firstOf(applicationComment()), "Windows")) return webkitOs();
        String fullOs = orEmpty(itunesFullOs());
        for (String name : new String[] {"Windows 8.1", "Windows 8", "Windows 7", "Windows Vista", "Windows XP"}) {
            if (fullOs.contains(name)) return name;
        }
        return "Windows";
    }

    /// `ITunes#full_os`: the comment was cut at the first ")", so "(Build 7601" gets it back.
    private String itunesFullOs() {
        String fullOs = at(applicationComment(), 1);
        if (fullOs == null) return null;
        int n = fullOs.length();
        boolean reopened = n >= 11 && fullOs.startsWith("(Build ", n - 11) && Strings.digitRun(fullOs, n - 4, n) == 4;
        return reopened ? fullOs + ")" : fullOs;
    }

    private String playstationOs() {
        List<String> comment = applicationComment();
        return comment == null ? null : String.join(" ", comment);
    }

    /// `PodcastAddict#os`, which raises for a comment-less Dalvik/Mozilla.
    private String podcastAddictOs() {
        if (products.size() <= 3) return null;
        Product device = products.get(3);
        if (!device.name.equals("Dalvik") && !device.name.equals("Mozilla")) return null;
        if (device.comment == null) throw Raised.INSTANCE;
        int n = device.comment.size();
        return n > 3 ? device.comment.get(2) : n == 3 ? "Android" : null;
    }

    private String geckoOs() {
        List<String> comment = applicationComment();
        if (comment == null) return null;
        String first = firstOf(comment);
        int index;
        if ("U".equals(at(comment, 1))) {
            index = 2;
        } else if (first != null && (first.startsWith("Windows ") || first.startsWith("Android"))) {
            index = 0;
        } else if ("Mobile".equals(first)) {
            return null;
        } else {
            index = 1;
        }
        String os = at(comment, index);
        return os == null ? null : normalizeOs(os);
    }

    /// `version.to_a[0]` compared with an Integer: nil raises NoMethodError, a String ArgumentError.
    private long windowsMediaPlayerMajor() {
        Version version = baseVersion();
        if (version == null) throw Raised.INSTANCE;
        if (version.toA().isEmpty() || !(version.toA().getFirst() instanceof Version.Int major)) throw Raised.INSTANCE;
        return major.value() < 0 ? Long.MAX_VALUE : major.value();
    }

    private String windowsMediaPlayerOs() {
        long major = windowsMediaPlayerMajor();
        List<Version.Segment> segments = baseVersion().toA();
        long part2 = part(segments, 2), part3 = part(segments, 3);

        if (major <= 4) {
            if (part3 == 3564 || part3 == 3925) return "Windows 98";
            if (part3 == 3857) return "Windows 9x";
            if (part3 == 3936) return "Windows XP";
            if (part3 == 3938) return "Windows 2000";
            return "Windows";
        }
        if (major == 7) return part3 == 3055 ? "Windows 98" : "Windows";
        if (major == 8) return "Windows XP";
        if (major == 9 || major == 10) {
            if (part3 == 2980) return "Windows 98/2000";
            if (part3 == 3268 || part3 == 3367 || part3 == 3270) return "Windows 2000";
            if (part3 == 3802 || part3 == 4503) return "Windows XP";
            return "Windows";
        }
        if (major == 11 || major == 12) {
            if (part2 == 9841 || part2 == 9858 || part2 == 9860 || part2 == 9879) return "Windows 10";
            if (part2 == 9651) return "Windows Phone 8.1";
            if (part2 == 9600) return "Windows 8.1";
            if (part2 == 9200) return "Windows 8";
            if (part2 == 7600 || part2 == 7601) return "Windows 7";
            if (part2 >= 6000 && part2 <= 6002) return "Windows Vista";
            if (part2 == 5721) return "Windows XP";
            return "Windows";
        }
        return "Windows";
    }

    private static long part(List<Version.Segment> segments, int index) {
        return index < segments.size() && segments.get(index) instanceof Version.Int i ? i.value() : -1;
    }

    // --- bot? and mobile? --------------------------------------------------------------------------

    /// `bot?`.
    boolean isBot() {
        if (application == null) return true;
        for (Product p : products) {
            if (p.comment == null) continue;
            for (String c : p.comment) {
                if (Strings.containsIgnoringCase(c, "bot")) return true;
            }
        }
        return detect("chrome-lighthouse") != null || application.name.contains("bot");
    }

    /// `mobile?`, which Campfire doesn't use (ApplicationPlatform has its own); kept for the gem's
    /// vectors.
    boolean tryMobile() {
        return switch (kind) {
            case OPERA -> operaMini();
            case PLAYSTATION -> "PlayStation Vita".equals(playstationPlatform());
            case PODCAST_ADDICT -> true;
            case WINDOWS_MEDIA_PLAYER -> {
                String os = windowsMediaPlayerOs();
                yield os.equals("Windows Phone 8") || os.equals("Windows Phone 8.1");
            }
            default -> detect("mobile") != null
                    || products.stream().anyMatch(p -> p.comment != null && p.comment.contains("Mobile"))
                    || contains(tryOs(), "Android")
                    || (applicationComment() != null && applicationComment().stream().anyMatch(c -> c.startsWith("IEMobile")));
        };
    }

    // --- UserAgent::OperatingSystems and the gem's regexps ---------------------------------------------

    private static final Pattern MAC_OS_X = Pattern.compile("(?:Intel|PPC) Mac OS X\\s*([0-9_.]+)?");
    /// `IOS_VERSION_REGEX`.
    private static final Pattern IOS_VERSION = Pattern.compile("CPU (?:iPhone |iPod )?OS ([0-9_]+) like Mac OS X");
    private static final Pattern CHROME_OS = Pattern.compile("CrOS\\s([^\\s]+)\\s(\\d+(?:\\.\\d+)*)");
    private static final Pattern WINDOWS_OS = Pattern.compile("Windows NT [\\d.]+|Windows Phone (?:OS )?[\\d.]+");
    private static final Pattern IE_VERSION = Pattern.compile("(?:MSIE\\s|rv:)([\\d.]+)");

    /// `UserAgent::OperatingSystems.normalize_os`.
    static String normalizeOs(String os) {
        String windows = switch (os) {
            case "Windows NT 10.0" -> "Windows 10";
            case "Windows NT 6.3" -> "Windows 8.1";
            case "Windows NT 6.2" -> "Windows 8";
            case "Windows NT 6.1" -> "Windows 7";
            case "Windows NT 6.0" -> "Windows Vista";
            case "Windows NT 5.2" -> "Windows XP x64 Edition";
            case "Windows NT 5.1" -> "Windows XP";
            case "Windows NT 5.01" -> "Windows 2000, Service Pack 1 (SP1)";
            case "Windows NT 5.0" -> "Windows 2000";
            case "Windows NT 4.0", "Windows 98", "Windows 95", "Windows CE" -> os;
            default -> null;
        };
        if (windows != null) return windows;

        var mac = MAC_OS_X.matcher(os);
        if (mac.find()) return mac.group(1) == null ? "OS X" : "OS X " + mac.group(1).replace('_', '.');
        var ios = IOS_VERSION.matcher(os);
        if (ios.find()) return "iOS " + ios.group(1).replace('_', '.');
        var chromeOs = CHROME_OS.matcher(os);
        if (chromeOs.find()) return "ChromeOS " + chromeOs.group(2);
        return os;
    }

    private static String group(Pattern pattern, String s, int group) {
        var matcher = pattern.matcher(s);
        return matcher.find() ? matcher.group(group) : null;
    }

    /// `joined_comment =~ /Trident.+rv:/`. Of the "Trident"s on one line the first matches if any
    /// does, so the rest of that line is skipped.
    private static boolean tridentRv(String s) {
        int at = s.indexOf("Trident");
        while (at >= 0) {
            int after = at + "Trident".length();
            int lineEnd = s.indexOf('\n', after);
            if (lineEnd < 0) lineEnd = s.length();
            int rv = s.indexOf("rv:", after + 1);
            if (rv >= 0 && rv < lineEnd) return true;
            if (lineEnd == s.length()) return false;
            at = s.indexOf("Trident", lineEnd + 1);
        }
        return false;
    }

    /// `WEBKIT_VERSION_REGEXP = /\A(?<webkit>AppleWebKit)\/(?<version>[\d\.]+)/i`: the version.
    /// The first 11 characters are downcased as a whole, as `String#downcase` would.
    private static String webkitCommentVersion(String comment) {
        int end = 0;
        for (int k = 0; k < 11; k++) {
            if (end >= comment.length()) return null;
            end += Character.charCount(comment.codePointAt(end));
        }
        String name = comment.substring(0, end);
        if (!Strings.downcase(name).equals("applewebkit")) return null;
        if (end >= comment.length() || comment.charAt(end) != '/') return null;
        int run = Strings.versionRun(comment, end + 1);
        return run > 0 ? comment.substring(end + 1, end + 1 + run) : null;
    }

    /// `Webkit::BuildVersions`: Safari before 3 reported only the WebKit build.
    private static String webkitBuildVersion(String build) {
        return switch (build) {
            case "85.7" -> "1.0";
            case "85.8.5", "85.8.2" -> "1.0.3";
            case "124" -> "1.2";
            case "125.2" -> "1.2.2";
            case "125.4" -> "1.2.3";
            case "125.5.5", "125.5.6", "125.5.7" -> "1.2.4";
            case "312.1.1", "312.1" -> "1.3";
            case "312.5", "312.5.1", "312.5.2" -> "1.3.1";
            case "312.8", "312.8.1" -> "1.3.2";
            case "412", "412.6", "412.6.2" -> "2.0";
            case "412.7" -> "2.0.1";
            case "416.11", "416.12" -> "2.0.2";
            case "417.9", "418" -> "2.0.3";
            case "418.8", "418.9", "418.9.1", "419" -> "2.0.4";
            case "425.13" -> "2.2";
            case "534.52.7" -> "5.1.2";
            default -> null;
        };
    }

    // --- Small helpers -------------------------------------------------------------------------------

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean contains(String s, String needle) {
        return s != null && s.contains(needle);
    }

    private static String at(List<String> list, int index) {
        return list != null && index < list.size() ? list.get(index) : null;
    }

    private static String firstOf(List<String> list) {
        return at(list, 0);
    }

    private static boolean anyContains(List<String> list, String needle) {
        if (list == null) return false;
        for (String s : list) {
            if (s.contains(needle)) return true;
        }
        return false;
    }
}
