package campfire.integrations.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A URL as Ruby's `URI.parse` accepts it, read with {@link URI}. `URI` is stricter in two places
/// Ruby isn't, and stored webhook and push endpoint URLs must keep parsing: a query may hold any
/// character but `#` (Ruby escapes the rest, and so does {@link #parse}), and a host may be any
/// RFC 3986 registered name, such as a Compose service called `bot_host`, where `URI` only takes
/// RFC 2396 host names and leaves the host null. Non-ASCII is rejected, as Ruby does.
///
/// `scheme` is downcased (null for a relative reference), `host` keeps IPv6 brackets, and `port`
/// is the scheme's default when absent (-1 for schemes without one).
public record Url(URI uri, String scheme, String host, int port) {
    public static Url parse(String text) throws URISyntaxException {
        if (text == null) throw new URISyntaxException("null", "no URL");
        if (text.chars().anyMatch(c -> c > 0x7f)) throw new URISyntaxException(text, "not ASCII");
        URI uri = new URI(escapeQuery(text));
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null && uri.getRawAuthority() != null) {
            String authority = uri.getRawAuthority();
            Matcher m = REGISTERED_NAME.matcher(authority.substring(authority.lastIndexOf('@') + 1));
            if (!m.matches()) throw new URISyntaxException(text, "invalid authority");
            host = m.group(1);
            port = m.group(2) == null || m.group(2).isEmpty() ? -1 : Integer.parseInt(m.group(2));
        }
        if (port == -1) port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
        return new Url(uri, scheme, host, port);
    }

    /// {@link #parse}, or null where it throws.
    public static Url parseOrNull(String text) {
        try {
            return parse(text);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /// RFC 3986 `reg-name` and an optional port.
    private static final Pattern REGISTERED_NAME = Pattern.compile("((?:[A-Za-z0-9\\-._~!$&'()*+,;=]|%\\p{XDigit}{2})+)(?::(\\d{0,5}))?");

    /// Characters `URI` accepts in a query: unreserved, reserved and the brackets.
    private static final String QUERY_CHARACTERS = "-_.!~*'();/?:@&=+$,[]";

    /// `URI::Generic#query=`: everything but valid `%XX` escapes and the characters a query may
    /// hold is escaped.
    private static String escapeQuery(String text) {
        int start = text.indexOf('?');
        int fragment = text.indexOf('#');
        if (start < 0 || (fragment >= 0 && fragment < start)) return text;
        int end = fragment < 0 ? text.length() : fragment;
        var out = new StringBuilder(text.length() + 16).append(text, 0, start + 1);
        for (int i = start + 1; i < end; i++) {
            char c = text.charAt(i);
            boolean escape = c == '%' && i + 2 < end && HexFormat.isHexDigit(text.charAt(i + 1)) && HexFormat.isHexDigit(text.charAt(i + 2));
            if (escape || Character.isLetterOrDigit(c) || QUERY_CHARACTERS.indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
            }
        }
        return out.append(text, end, text.length()).toString();
    }

    /// `uri.is_a?(URI::HTTP)`, which includes `URI::HTTPS`.
    public boolean isHttp() {
        return "http".equals(scheme) || "https".equals(scheme);
    }

    public boolean isHttps() {
        return "https".equals(scheme);
    }

    /// The host without IPv6 brackets.
    public String hostname() {
        return host != null && host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /// `URI::HTTP#request_uri`: the path (at least "/") and query.
    public String requestUri() {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    /// The same URL on another host.
    public Url withHost(String newHost) {
        var s = new StringBuilder(scheme).append("://");
        if (uri.getRawUserInfo() != null) s.append(uri.getRawUserInfo()).append('@');
        s.append(newHost);
        if (uri.getPort() != -1) s.append(':').append(uri.getPort());
        s.append(uri.getRawPath());
        if (uri.getRawQuery() != null) s.append('?').append(uri.getRawQuery());
        if (uri.getRawFragment() != null) s.append('#').append(uri.getRawFragment());
        return parseOrNull(s.toString());
    }

    @Override
    public String toString() {
        return uri.toString();
    }
}
