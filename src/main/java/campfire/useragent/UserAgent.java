package campfire.useragent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// What Campfire reads from a User-Agent: the browser's name and version, the platform from its
/// comment (`Macintosh`, `Windows`, `iPhone`, …), the operating system, and whether it's a bot. It
/// reads as the useragent gem Rails uses does for browsers and bots, so the incompatible-browser
/// check, the PWA help pages and the push subscriptions list match Rails; media players, consoles,
/// podcast apps, WeChat, Opera Mini and pre-2008 Safari get the plain first-product reading.
///
/// Modern Edge (`Edg/`) reads as Chrome, as it does for the gem; only EdgeHTML's `Edge/` is "Edge".
@NullMarked
public record UserAgent(String browser, String version, String platform, String os, boolean bot) {
    /// `Name/version (comment; parts)`; `comment` is null when there's none.
    private record Product(String name, String version, @Nullable List<String> comment) {}

    /// The gem's grammar: products one after another from the start, a comment only after a single
    /// space, and Google's proxy's `,gzip(gfe)` suffix skipped.
    private static final Pattern PRODUCT = Pattern.compile("['\"]*([^/\\s]+)/?([^\\s,]*)(?:\\s\\(([^)]*)\\)|,gzip\\(gfe\\))?");
    private static final Pattern WEBKIT_COMMENT = Pattern.compile("(?i)AppleWebKit/[\\d.]+.*");
    private static final Pattern IE_VERSION = Pattern.compile("(?:MSIE\\s|rv:)([\\d.]+)");
    private static final Pattern WINDOWS = Pattern.compile("Windows NT [\\d.]+|Windows Phone (?:OS )?[\\d.]+");
    private static final Pattern MAC_OS = Pattern.compile("(?:Intel|PPC) Mac OS X\\s*([0-9_.]+)?");
    private static final Pattern IOS = Pattern.compile("CPU (?:iPhone |iPod )?OS ([0-9_]+) like Mac OS X");
    private static final Pattern CHROME_OS = Pattern.compile("CrOS\\s\\S+\\s(\\d+(?:\\.\\d+)*)");

    /// A blank header reads as the gem's default, `Mozilla/4.0 (compatible)`.
    public static UserAgent parse(@Nullable String header) {
        String ua = header == null || header.chars().allMatch(c -> c <= ' ') ? "Mozilla/4.0 (compatible)" : header;
        var products = new ArrayList<Product>();
        Matcher m = PRODUCT.matcher(ua);
        int pos = 0;
        while (pos < ua.length() && m.region(pos, ua.length()).lookingAt()) {
            String comment = m.group(3);
            products.add(new Product(m.group(1), m.group(2), comment == null ? null : List.of(comment.split("; "))));
            pos = m.end();
            while (pos < ua.length() && Character.isWhitespace(ua.charAt(pos))) pos++;
        }
        if (products.isEmpty()) return new UserAgent("", "", "", "", true);
        Product first = products.getFirst(), last = products.getLast();

        if (last.name().equals("Edge")) return of(products, first, "Edge", last.version(), "Windows", windows(first));
        List<String> comment = comment(first);
        if (first.comment() != null && (at(comment, 1).contains("MSIE") || String.join("; ", comment).matches(".*Trident.+rv:.*"))) {
            return of(products, first, "Internet Explorer", group(IE_VERSION, String.join("; ", comment)), "Windows", windows(first));
        }
        if (first.name().equals("Opera") || last.name().equals("OPR")) {
            Product version = find(products, "version") != null ? find(products, "version") : find(products, "opr");
            boolean windows = at(comment, 0).contains("Windows");
            return of(products, first, "Opera", version == null ? first.version() : version.version(), windows ? "Windows" : at(comment, 0),
                windows ? normalize(at(comment, 0)) : at(comment, 1));
        }
        // These read their comment from the first product that has one, and with none are bots.
        Product application = products.stream().filter(p -> p.comment() != null).findFirst().orElse(null);
        List<String> parts = comment(application);
        if (has(products, "Vivaldi")) return of(products, application, "Vivaldi", last.version(), chromePlatform(parts), webkitOs(parts));
        Product chrome = find(products, "crios") != null ? find(products, "crios") : find(products, "chrome");
        if (chrome != null && (has(products, "Chrome") || has(products, "CriOS"))) {
            return of(products, application, find(products, "iron") != null ? "Iron" : "Chrome", chrome.version(), chromePlatform(parts), webkitOs(parts));
        }
        if (find(products, "applewebkit") != null || products.stream().anyMatch(p -> comment(p).stream().anyMatch(c -> WEBKIT_COMMENT.matcher(c).matches()))) {
            String os = webkitOs(parts);
            String platform = at(parts, 0).contains("Windows") ? "Windows" : at(parts, 0).equals("BB10") ? "BlackBerry"
                : parts.stream().anyMatch(c -> c.contains("Android")) ? "Android" : at(parts, 0);
            String name = os.contains("Android") ? "Android" : platform.equals("BlackBerry") ? "BlackBerry" : "Safari";
            Product version = find(products, "version");
            String ios = os.startsWith("iOS ") && name.equals("Safari") ? os.substring(4) : "";
            return of(products, application, name, version != null ? version.version() : ios, platform, os);
        }
        if (first.name().equals("Mozilla")) {
            for (String name : List.of("PaleMoon", "Firefox", "Camino", "Iceweasel", "Seamonkey")) {
                Product gecko = find(products, name.toLowerCase(Locale.ROOT));
                if (gecko != null) return of(products, first, name, gecko.version().isEmpty() ? first.version() : gecko.version(), geckoPlatform(comment), geckoOs(comment));
            }
            return of(products, first, "Mozilla", first.version(), geckoPlatform(comment), geckoOs(comment));
        }
        return of(products, first, first.name(), first.version(), "", "");
    }

    private static UserAgent of(List<Product> products, @Nullable Product application, String browser, String version, String platform, String os) {
        boolean bot = application == null || application.name().contains("bot") || find(products, "chrome-lighthouse") != null
            || products.stream().anyMatch(p -> comment(p).stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).contains("bot")));
        return new UserAgent(browser, version, platform, os, bot);
    }

    private static String chromePlatform(List<String> comment) {
        if (at(comment, 0).contains("Windows")) return "Windows";
        if (comment.stream().anyMatch(c -> c.contains("CrOS"))) return "ChromeOS";
        return comment.stream().anyMatch(c -> c.contains("Android")) ? "Android" : at(comment, 0);
    }

    private static String geckoPlatform(List<String> comment) {
        String first = at(comment, 0);
        if (first.isEmpty() || first.equals("compatible") || first.equals("Mobile")) return "";
        return first.startsWith("Windows ") ? "Windows" : first;
    }

    /// The comment part naming the system: the first for Windows, else the second, or the third
    /// when there is one and the second isn't Android (`X11; Linux x86_64`, `iPhone; CPU iPhone OS …`).
    private static String webkitOs(List<String> comment) {
        if (at(comment, 0).contains("Windows NT")) return normalize(at(comment, 0));
        if (at(comment, 2).isEmpty() || at(comment, 1).contains("Android")) return normalize(at(comment, 1));
        return normalize(comment.stream().filter(c -> IOS.matcher(c).find()).findFirst().orElse(at(comment, 2)));
    }

    private static String geckoOs(List<String> comment) {
        String first = at(comment, 0);
        if (at(comment, 1).equals("U")) return normalize(at(comment, 2));
        if (first.startsWith("Windows ") || first.startsWith("Android")) return normalize(first);
        return first.equals("Mobile") ? "" : normalize(at(comment, 1));
    }

    private static String windows(Product first) {
        return normalize(group(WINDOWS, String.join("; ", comment(first)), 0));
    }

    /// The gem's names for Windows NT releases, macOS, iOS and ChromeOS versions.
    private static String normalize(String os) {
        String windows = switch (os) {
            case "Windows NT 10.0" -> "Windows 10";
            case "Windows NT 6.3" -> "Windows 8.1";
            case "Windows NT 6.2" -> "Windows 8";
            case "Windows NT 6.1" -> "Windows 7";
            case "Windows NT 6.0" -> "Windows Vista";
            case "Windows NT 5.1" -> "Windows XP";
            default -> null;
        };
        if (windows != null) return windows;
        Matcher mac = MAC_OS.matcher(os);
        if (mac.find()) return mac.group(1) == null ? "OS X" : "OS X " + mac.group(1).replace('_', '.');
        Matcher ios = IOS.matcher(os);
        if (ios.find()) return "iOS " + ios.group(1).replace('_', '.');
        Matcher chromeOs = CHROME_OS.matcher(os);
        return chromeOs.find() ? "ChromeOS " + chromeOs.group(1) : os;
    }

    private static List<String> comment(@Nullable Product product) {
        return product == null || product.comment() == null ? List.of() : product.comment();
    }

    /// Detection compares names exactly; lookups (`version`, `chrome`, …) ignore case, as the gem's do.
    private static boolean has(List<Product> products, String name) {
        return products.stream().anyMatch(p -> p.name().equals(name));
    }

    private static @Nullable Product find(List<Product> products, String lowercase) {
        for (Product p : products) if (p.name().toLowerCase(Locale.ROOT).equals(lowercase)) return p;
        return null;
    }

    private static String at(List<String> list, int index) {
        return index < list.size() ? list.get(index) : "";
    }

    private static String group(Pattern pattern, String s) {
        return group(pattern, s, 1);
    }

    private static String group(Pattern pattern, String s, int group) {
        Matcher m = pattern.matcher(s);
        return m.find() ? m.group(group) : "";
    }
}
