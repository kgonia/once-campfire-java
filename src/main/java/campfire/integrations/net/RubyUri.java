package campfire.integrations.net;

import java.util.Locale;

/// The slice of Ruby's `URI.parse` (uri 1.1, RFC 3986 parser) that the opengraph URL checks,
/// tweet URL rewriting and push endpoint validation rely on, including which inputs raise which
/// error. Components are as `URI::Generic` keeps them; `port` is the scheme default when absent.
public record RubyUri(String scheme, String userinfo, String host, Long port, String path, String opaque, String query, String fragment) {

    /// What `URI.parse` raises.
    public static final class InvalidUriException extends Exception {
        /// `URI::InvalidComponentError` (from `URI::MailTo`), which callers don't rescue, when
        /// set; otherwise `URI::InvalidURIError`, which they do.
        private final boolean component;

        InvalidUriException(boolean component) {
            super(component ? "URI::InvalidComponentError" : "URI::InvalidURIError", null, false, false);
            this.component = component;
        }

        public boolean invalidComponent() {
            return component;
        }

        /// The Ruby exception class.
        public String className() {
            return getMessage();
        }
    }

    /// `URI.parse(value)`
    public static RubyUri parse(String value) throws InvalidUriException {
        if (value == null) throw new InvalidUriException(false);
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 0x7f) throw new InvalidUriException(false);
        RubyUri uri = splitAbsolute(value);
        if (uri == null) uri = splitRelative(value);
        if (uri == null) throw new InvalidUriException(false);
        String query = uri.query == null ? null : escapeQuery(uri.query);
        Long port = uri.port != null ? uri.port : defaultPort(uri.scheme);
        uri = new RubyUri(uri.scheme, uri.userinfo, uri.host, port, uri.path, uri.opaque, query, uri.fragment);
        checkSchemeClass(uri);
        return uri;
    }

    /// `URI.parse(value)`, or null where it raises `URI::InvalidURIError` (`rescue nil`
    /// callers); `URI::InvalidComponentError` comes back as null too.
    public static RubyUri parseOrNull(String value) {
        try {
            return parse(value);
        } catch (InvalidUriException e) {
            return null;
        }
    }

    /// `uri.is_a?(URI::HTTP)`, which includes `URI::HTTPS`.
    public boolean isHttp() {
        return "http".equals(scheme) || "https".equals(scheme);
    }

    public boolean isHttps() {
        return "https".equals(scheme);
    }

    /// `URI#hostname`: the host without IPv6 brackets.
    public String hostname() {
        if (host != null && host.startsWith("[") && host.endsWith("]")) return host.substring(1, host.length() - 1);
        return host;
    }

    /// `URI::HTTP#request_uri`: path (at least "/") and query.
    public String requestUri() {
        String p = path == null ? "" : path;
        String target = query != null ? p + "?" + query : p;
        return target.startsWith("/") ? target : "/" + target;
    }

    public RubyUri withHost(String newHost) {
        return new RubyUri(scheme, userinfo, newHost, port, path, opaque, query, fragment);
    }

    private static Long defaultPort(String scheme) {
        if (scheme == null) return null;
        return switch (scheme.toUpperCase(Locale.ROOT)) {
            case "HTTP", "WS" -> 80L;
            case "HTTPS", "WSS" -> 443L;
            case "FTP" -> 21L;
            case "LDAP" -> 389L;
            case "LDAPS" -> 636L;
            default -> null;
        };
    }

    /// `URI::Generic#to_s`
    @Override
    public String toString() {
        var s = new StringBuilder();
        if (scheme != null) s.append(scheme).append(':');
        if (opaque != null) {
            s.append(opaque);
        } else {
            if (host != null || "file".equals(scheme) || "postgres".equals(scheme)) s.append("//");
            if (userinfo != null) s.append(userinfo).append('@');
            if (host != null) s.append(host);
            if (port != null && !port.equals(defaultPort(scheme))) s.append(':').append(port);
            if (path != null) s.append(path);
            if (query != null) s.append('?').append(query);
        }
        if (fragment != null) s.append('#').append(fragment);
        return s.toString();
    }

    /// The initializers of the scheme classes `URI.for` picks that can raise.
    private static void checkSchemeClass(RubyUri uri) throws InvalidUriException {
        if (uri.scheme == null) return;
        switch (uri.scheme.toUpperCase(Locale.ROOT)) {
            case "MAILTO" -> {
                String opaque = uri.opaque != null ? uri.opaque : uri.query != null ? "?" + uri.query : null;
                if (opaque == null) throw new InvalidUriException(true);
                int q = opaque.indexOf('?');
                String to = q >= 0 ? opaque.substring(0, q) : opaque;
                if (!mailtoToValid(to)) throw new InvalidUriException(true);
            }
            case "LDAP", "LDAPS" -> {
                if (uri.fragment != null || uri.path == null) throw new InvalidUriException(false);
            }
            case "FTP" -> {
                if (uri.path == null) throw new InvalidUriException(false);
            }
            default -> {}
        }
    }

    /// `/\A(?:[^@,;]+@[^@,;]+(?:\z|[,;]))*\z/`
    private static boolean mailtoToValid(String to) {
        int i = 0, n = to.length();
        while (i < n) {
            int start = i;
            while (i < n && !special(to.charAt(i))) i++;
            if (i == start || i >= n || to.charAt(i) != '@') return false;
            i++;
            int domain = i;
            while (i < n && !special(to.charAt(i))) i++;
            if (i == domain) return false;
            if (i < n) {
                if (to.charAt(i) == '@') return false;
                i++;
            }
        }
        return true;
    }

    private static boolean special(char c) {
        return c == '@' || c == ',' || c == ';';
    }

    /// `URI::Generic#query=`: rejects `%` followed by two non-hex characters and escapes the rest.
    private static String escapeQuery(String query) throws InvalidUriException {
        var cleaned = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c != '\t' && c != '\r' && c != '\n') cleaned.append(c);
        }
        for (int i = 0; i + 2 < cleaned.length(); i++) {
            if (cleaned.charAt(i) == '%' && !isHex(cleaned.charAt(i + 1)) && !isHex(cleaned.charAt(i + 2))) throw new InvalidUriException(false);
        }
        var out = new StringBuilder();
        for (int i = 0; i < cleaned.length(); i++) {
            char b = cleaned.charAt(i);
            boolean escape = i + 2 < cleaned.length() && b == '%' && isHex(cleaned.charAt(i + 1)) && isHex(cleaned.charAt(i + 2));
            if (escape || b == '!' || (b >= '$' && b <= '&') || (b >= '(' && b <= ';') || b == '=' || (b >= '?' && b <= '_')
                || (b >= 'a' && b <= '~')) {
                out.append(b);
            } else {
                out.append('%').append(String.format("%02X", (int) b));
            }
        }
        return out.toString();
    }

    // --- RFC 3986 matching, mirroring URI::RFC3986_Parser::RFC3986_URI ------------------------

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /// `[!$&-.0-9;=A-Z_a-z~]`: `&-.` covers `& ' ( ) * + , - .`
    private static boolean unreservedOrSub(char b) {
        return b == '!' || b == '$' || (b >= '&' && b <= '.') || isDigit(b) || b == ';' || b == '=' || (b >= 'A' && b <= 'Z') || b == '_'
            || (b >= 'a' && b <= 'z') || b == '~';
    }

    private interface CharClass {
        boolean test(char c);
    }

    private static boolean pctAt(String s, int i) {
        return i + 2 < s.length() && s.charAt(i) == '%' && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2));
    }

    /// Consumes `(?:%\h\h|[class])*` possessively and returns the end index (within `limit`).
    private static int takeWhile(String s, int i, int limit, CharClass cls) {
        while (true) {
            if (i + 2 < limit && pctAt(s, i)) i += 3;
            else if (i < limit && cls.test(s.charAt(i))) i++;
            else return i;
        }
    }

    private static boolean segChar(char b) {
        return unreservedOrSub(b) || b == ':' || b == '@' || b == '/';
    }

    private static boolean segNcChar(char b) {
        return unreservedOrSub(b) || b == '@';
    }

    private static boolean fragmentChar(char b) {
        return unreservedOrSub(b) || b == ':' || b == '@' || b == '/' || b == '?';
    }

    private static boolean userinfoChar(char b) {
        return unreservedOrSub(b) || b == ':';
    }

    /// The IP-literal alternative of HOST (a bracketed address); its end, or -1.
    private static int ipLiteral(String s, int i, int limit) {
        if (i >= limit || s.charAt(i) != '[') return -1;
        int close = s.indexOf(']', i);
        if (close < 0 || close >= limit) return -1;
        String inner = s.substring(i + 1, close);
        boolean valid;
        if (inner.startsWith("v") || inner.startsWith("V")) {
            String future = inner.substring(1);
            int dot = future.indexOf('.');
            if (dot < 0) return -1;
            String hex = future.substring(0, dot), rest = future.substring(dot + 1);
            valid = !hex.isEmpty() && hex.chars().allMatch(c -> isHex((char) c)) && !rest.isEmpty()
                && rest.chars().allMatch(c -> unreservedOrSub((char) c) || c == ':');
        } else {
            valid = Guard.parseIpv6(inner) != null;
        }
        return valid ? close + 1 : -1;
    }

    private record Tail(int hierEnd, int queryStart, int queryEnd, int fragmentStart) {}

    /// Splits off `(?:\?(?<query>[^#]*+))?(?:\#(?<fragment>FRAGMENT))?\z`.
    private static Tail tail(String s, int start) {
        int hierEnd = start;
        while (hierEnd < s.length() && s.charAt(hierEnd) != '?' && s.charAt(hierEnd) != '#') hierEnd++;
        int i = hierEnd, queryStart = -1, queryEnd = -1, fragmentStart = -1;
        if (i < s.length() && s.charAt(i) == '?') {
            queryStart = i + 1;
            int end = s.indexOf('#', queryStart);
            queryEnd = end < 0 ? s.length() : end;
            i = queryEnd;
        }
        if (i < s.length() && s.charAt(i) == '#') {
            int end = takeWhile(s, i + 1, s.length(), RubyUri::fragmentChar);
            if (end != s.length()) return null;
            fragmentStart = i + 1;
            i = s.length();
        }
        return i == s.length() ? new Tail(hierEnd, queryStart, queryEnd, fragmentStart) : null;
    }

    private record Authority(String userinfo, String host, Long port, int end) {}

    private static Authority authority(String s, int start, int limit) {
        int i = start;
        String userinfo = null;
        int uiEnd = takeWhile(s, i, limit, RubyUri::userinfoChar);
        if (uiEnd < limit && s.charAt(uiEnd) == '@') {
            userinfo = s.substring(i, uiEnd);
            i = uiEnd + 1;
        }
        int hostEnd = ipLiteral(s, i, limit);
        if (hostEnd < 0) hostEnd = takeWhile(s, i, limit, RubyUri::unreservedOrSub);
        String host = s.substring(i, hostEnd);
        int end = hostEnd;
        Long port = null;
        if (end < limit && s.charAt(end) == ':') {
            int digitsEnd = end + 1;
            while (digitsEnd < limit && isDigit(s.charAt(digitsEnd))) digitsEnd++;
            String digits = s.substring(end + 1, digitsEnd);
            if (!digits.isEmpty()) {
                try {
                    port = Long.parseLong(digits);
                } catch (NumberFormatException e) {
                    port = Long.MAX_VALUE;
                }
            }
            end = digitsEnd;
        }
        if (end < limit && s.charAt(end) != '/') return null;
        return new Authority(userinfo, host, port, end);
    }

    private static String sub(String s, int start, int end) {
        return start < 0 ? null : s.substring(start, end);
    }

    private static RubyUri splitAbsolute(String s) {
        if (s.isEmpty() || !isAlpha(s.charAt(0))) return null;
        int i = 1;
        while (i < s.length() && (isAlpha(s.charAt(i)) || isDigit(s.charAt(i)) || s.charAt(i) == '+' || s.charAt(i) == '-' || s.charAt(i) == '.')) i++;
        if (i >= s.length() || s.charAt(i) != ':') return null;
        // URI::Generic#set_scheme downcases it.
        String scheme = s.substring(0, i).toLowerCase(Locale.ROOT);
        int restStart = i + 1;
        Tail tail = tail(s, restStart);
        if (tail == null) return null;
        String query = sub(s, tail.queryStart(), tail.queryEnd());
        String fragment = sub(s, tail.fragmentStart(), s.length());
        String hier = s.substring(restStart, tail.hierEnd());
        if (hier.startsWith("//")) {
            Authority a = authority(s, restStart + 2, tail.hierEnd());
            if (a == null) return null;
            // path-abempty: (?:/seg*)?
            if (a.end() < tail.hierEnd()
                && (s.charAt(a.end()) != '/' || takeWhile(s, a.end(), tail.hierEnd(), RubyUri::segChar) != tail.hierEnd())) {
                return null;
            }
            return new RubyUri(scheme, a.userinfo(), a.host(), a.port(), s.substring(a.end(), tail.hierEnd()), null, query, fragment);
        } else if (hier.startsWith("/")) {
            if (takeWhile(s, restStart, tail.hierEnd(), RubyUri::segChar) != tail.hierEnd()) return null;
            return new RubyUri(scheme, null, null, null, hier, null, query, fragment);
        } else if (!hier.isEmpty()) {
            // path-rootless becomes the opaque part, with the query folded back in
            if (takeWhile(s, restStart, tail.hierEnd(), RubyUri::segChar) != tail.hierEnd()) return null;
            String opaque = query != null ? hier + "?" + query : hier;
            return new RubyUri(scheme, null, null, null, null, opaque, null, fragment);
        } else {
            return new RubyUri(scheme, null, null, null, "", null, query, fragment);
        }
    }

    /// RFC3986_relative_ref: only validity matters here, since a relative reference is never HTTP.
    private static RubyUri splitRelative(String s) {
        Tail tail = tail(s, 0);
        if (tail == null) return null;
        int end = tail.hierEnd();
        String hier = s.substring(0, end);
        boolean valid;
        if (hier.startsWith("//")) {
            Authority a = authority(s, 2, end);
            valid = a != null && takeWhile(s, a.end(), end, RubyUri::segChar) == end;
        } else if (hier.startsWith("/") || hier.isEmpty()) {
            valid = takeWhile(s, 0, end, RubyUri::segChar) == end;
        } else {
            int first = takeWhile(s, 0, end, RubyUri::segNcChar);
            valid = first > 0 && (first == end || (s.charAt(first) == '/' && takeWhile(s, first, end, RubyUri::segChar) == end));
        }
        if (!valid) return null;
        return new RubyUri(null, null, null, null, hier, null, sub(s, tail.queryStart(), tail.queryEnd()), sub(s, tail.fragmentStart(), s.length()));
    }
}
