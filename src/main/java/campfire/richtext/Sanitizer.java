package campfire.richtext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/// `Rails::HTML5::SafeListSanitizer` (Loofah's permit scrubber): disallowed HTML elements are
/// unwrapped, foreign (SVG/MathML) ones dropped with their contents, disallowed attributes removed,
/// URI attributes checked against Loofah's protocols. Styles keep only plain color values.
public final class Sanitizer {
    private Sanitizer() {}

    public record SafeList(Set<String> tags, Set<String> attributes) {}

    static final List<String> DEFAULT_ALLOWED_TAGS = List.of("a", "abbr", "acronym", "address", "b", "big", "blockquote", "br", "cite", "code",
        "dd", "del", "dfn", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "ins", "kbd", "li", "mark", "ol", "p",
        "pre", "samp", "small", "span", "strong", "sub", "sup", "time", "tt", "ul", "var");
    static final List<String> DEFAULT_ALLOWED_ATTRIBUTES = List.of("abbr", "alt", "cite", "class", "datetime", "height", "href", "lang", "src",
        "title", "width", "xml:lang");
    static final List<String> EDITOR_FORMATTING_TAGS = List.of("s", "u", "mark", "table", "thead", "tbody", "tfoot", "tr", "th", "td");
    static final List<String> EDITOR_FORMATTING_ATTRIBUTES = List.of("data-language");
    /// `ActionText::Attachment::ATTRIBUTES`
    public static final List<String> ATTACHMENT_ATTRIBUTES = List.of("sgid", "content-type", "url", "href", "filename", "filesize", "width",
        "height", "previewable", "presentation", "caption", "content");

    /// `ContentFilters::SanitizeTags::ALLOWED_TAGS`
    static final Set<String> SANITIZE_TAGS_ALLOWED = union(List.of("a", "abbr", "acronym", "address", "b", "big", "blockquote", "br", "cite",
        "code", "dd", "del", "dfn", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "ins", "kbd", "li", "ol", "p", "pre",
        "samp", "small", "span", "strong", "sub", "sup", "time", "tt", "ul", "var"), EDITOR_FORMATTING_TAGS,
        List.of("action-text-attachment", "figure", "figcaption"));

    public static final SafeList DEFAULTS = new SafeList(union(DEFAULT_ALLOWED_TAGS), union(DEFAULT_ALLOWED_ATTRIBUTES));

    /// Action Text's render-time allowlist (with Campfire's editor additions).
    public static final SafeList ACTION_TEXT = new SafeList(
        union(DEFAULT_ALLOWED_TAGS, List.of("action-text-attachment", "figure", "figcaption"),
            List.of("video", "audio", "source", "embed", "table", "tbody", "tr", "th", "td"), EDITOR_FORMATTING_TAGS),
        union(DEFAULT_ALLOWED_ATTRIBUTES, ATTACHMENT_ATTRIBUTES, List.of("controls", "poster", "data-language", "style", "value", "start"),
            EDITOR_FORMATTING_ATTRIBUTES));

    /// `ContentFilters::SanitizeAttributes`: SanitizeTags' tags, Action Text's attributes plus `class`.
    public static final SafeList CONTENT_FILTER = new SafeList(SANITIZE_TAGS_ALLOWED, union(new ArrayList<>(ACTION_TEXT.attributes()), List.of("class")));

    /// `MessagesHelper::AUTO_LINK_ALLOWED_TAGS/ATTRIBUTES`
    public static final SafeList AUTO_LINK = new SafeList(union(DEFAULT_ALLOWED_TAGS, EDITOR_FORMATTING_TAGS),
        union(DEFAULT_ALLOWED_ATTRIBUTES, EDITOR_FORMATTING_ATTRIBUTES));

    @SafeVarargs
    private static Set<String> union(List<String>... lists) {
        var set = new LinkedHashSet<String>();
        for (var list : lists) set.addAll(list);
        return Set.copyOf(set);
    }

    public static String sanitize(String html, SafeList list) {
        if (html == null || html.isEmpty()) return "";
        return Html.innerHtml(scrubbed(html, list));
    }

    public static String sanitizeEscapingAttributeBrackets(String html, SafeList list) {
        if (html == null || html.isEmpty()) return "";
        return Html.innerHtmlEscapingAttributeBrackets(scrubbed(html, list));
    }

    static Element scrubbed(String html, SafeList list) {
        Element root = Html.parseFragment(html);
        for (Node child : new ArrayList<>(root.childNodes())) scrubBottomUp(child, list);
        return root;
    }

    private static void scrubBottomUp(Node node, SafeList list) {
        for (Node child : new ArrayList<>(node.childNodes())) scrubBottomUp(child, list);
        scrub(node, list);
    }

    private static void scrub(Node node, SafeList list) {
        if (node instanceof TextNode) return;
        if (node instanceof DataNode data) {
            // Script and style contents become text when their element is unwrapped.
            if (node.parentNode() instanceof Element parent && !list.tags().contains(parent.normalName())) {
                node.replaceWith(new TextNode(data.getWholeData()));
            }
            return;
        }
        if (node instanceof Comment) {
            node.remove();
            return;
        }
        if (!(node instanceof Element element)) {
            node.remove();
            return;
        }
        if (!list.tags().contains(element.normalName())) {
            boolean foreign = !isHtmlNamespace(element);
            if (!foreign) {
                for (Node child : new ArrayList<>(element.childNodes())) {
                    if (child instanceof DataNode data) child = new TextNode(data.getWholeData());
                    element.before(child);
                }
            }
            element.remove();
            return;
        }
        scrubAttributes(element, list);
    }

    static boolean isHtmlNamespace(Element element) {
        String namespace = element.tag().namespace();
        return namespace == null || namespace.equals(org.jsoup.parser.Parser.NamespaceHtml);
    }

    private static final Set<String> URI_ATTRIBUTES = Set.of("action", "cite", "href", "longdesc", "poster", "preload", "src", "xlink:href",
        "xml:base");

    private static void scrubAttributes(Element element, SafeList list) {
        boolean isA = element.normalName().equals("a");
        for (Attribute attribute : new ArrayList<>(element.attributes().asList())) {
            String name = attribute.getKey();
            String value = attribute.getValue();
            if (!list.attributes().contains(name) || (URI_ATTRIBUTES.contains(name) && !allowedUri(value))) {
                element.removeAttr(name);
                continue;
            }
            if (name.equals("src") && value.chars().allMatch(Character::isWhitespace)) {
                element.removeAttr(name);
                continue;
            }
            boolean qualifies = name.equals("href") || name.equals("action") || name.equals("src") || (name.equals("name") && isA);
            if (qualifies && needsEscaping(value)) element.attr(name, escapeAttributeValue(value));
        }
        scrubStyle(element);
    }

    private static boolean needsEscaping(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '"' || (c < ' ' && c != '\t' && c != '\n' && c != '\r')) return true;
        }
        return false;
    }

    private static String escapeAttributeValue(String value) {
        var out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ') out.append("%20");
            else if (c == '"') out.append("%22");
            else if (c == '\t' || c == '\n' || c == '\r') out.append(c);
            else if (c >= ' ') out.append(c);
        }
        return out.toString();
    }

    private static final Set<String> ALLOWED_STYLE_PROPERTIES = Set.of("color", "background-color");
    private static final Pattern PLAIN_COLOR = Pattern.compile(
        "(?i)\\A(?:[a-z]+|#[0-9a-f]{3,8}|var\\(\\s*--[a-z0-9_-]+\\s*\\)|(?:rgb|rgba|hsl|hsla)\\([0-9a-z.,%\\s/+-]*\\))\\z");

    private static void scrubStyle(Element element) {
        if (!element.hasAttr("style")) return;
        String style = element.attr("style");
        var kept = new StringBuilder();
        boolean all = true;
        int declarations = 0;
        for (String declaration : style.split(";")) {
            if (declaration.isBlank()) continue;
            declarations++;
            int colon = declaration.indexOf(':');
            String property = (colon < 0 ? declaration : declaration.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
            String value = colon < 0 ? "" : declaration.substring(colon + 1).strip();
            if (ALLOWED_STYLE_PROPERTIES.contains(property) && PLAIN_COLOR.matcher(value).matches()) {
                kept.append(property).append(": ").append(value).append(';');
            } else {
                all = false;
            }
        }
        if (all && declarations > 0) return;
        if (kept.isEmpty()) element.removeAttr("style");
        else element.attr("style", kept.toString());
    }

    // -- Loofah's URI check ----------------------------------------------------------------------

    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("afs", "aim", "callto", "data", "ed2k", "fax", "ftp", "gopher", "http",
        "https", "irc", "line", "mailto", "modem", "news", "nntp", "rsync", "rtsp", "sftp", "sms", "ssh", "tag", "tel", "telnet", "urn",
        "webcal", "xmpp");
    private static final Set<String> ALLOWED_URI_DATA_MEDIATYPES = Set.of("image/gif", "image/jpeg", "image/png", "text/css", "text/plain");

    private static boolean isControl(char c) {
        return c == '`' || c <= ' ' || c == '\u007f' || (c >= '\u0080' && c <= 'ā');
    }

    private static String withoutControls(String s) {
        var out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) if (!isControl(s.charAt(i))) out.append(s.charAt(i));
        return out.toString();
    }

    public static boolean allowedUri(String uri) {
        String decoded = decodeNumericCharacterReferences(cgiUnescapeHtml(withoutControls(uri)));
        String s = withoutControls(decoded).replace("&Tab;", "").replace("&NewLine;", "").replace("&colon;", ":").toLowerCase(Locale.ROOT);
        String protocol = protocolBeforeSeparator(s);
        if (protocol == null) return true;
        if (!ALLOWED_PROTOCOLS.contains(protocol)) return false;
        if (protocol.equals("data")) {
            String mediatype = dataUriMediatype(s);
            return mediatype != null && ALLOWED_URI_DATA_MEDIATYPES.contains(mediatype);
        }
        return true;
    }

    private static String protocolBeforeSeparator(String s) {
        if (s.isEmpty() || !(s.charAt(0) >= 'a' && s.charAt(0) <= 'z')) return null;
        int end = 1;
        while (end < s.length()) {
            char c = s.charAt(end);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.') end++;
            else break;
        }
        return hasSeparator(s.substring(end)) ? s.substring(0, end) : null;
    }

    private static boolean hasSeparator(String s) {
        if (s.startsWith(":")) return true;
        if (s.startsWith("&#x")) {
            String rest = s.substring(3).replaceFirst("^0+", "");
            if (rest.startsWith("3a")) return true;
        }
        if (s.startsWith("&#")) {
            String rest = s.substring(2).replaceFirst("^0+", "");
            if (rest.startsWith("58")) return true;
        }
        return s.startsWith("%3a") || s.startsWith("&#37;3a");
    }

    private static String dataUriMediatype(String s) {
        String rest = s.startsWith("data:") ? s.substring(5) : s;
        int comma = rest.indexOf(',');
        if (comma < 0) return null;
        String metadata = rest.substring(0, comma);
        if (metadata.endsWith(";base64")) metadata = metadata.substring(0, metadata.length() - 7);
        String mediatype = metadata.split(";", -1)[0].replaceAll("^[ \\t\\n\\x0B\\f\\r\\x00]+|[ \\t\\n\\x0B\\f\\r\\x00]+$", "");
        int slash = mediatype.indexOf('/');
        boolean valid = slash > 0 && slash < mediatype.length() - 1 && isToken(mediatype.substring(0, slash)) && isToken(mediatype.substring(slash + 1));
        return valid ? mediatype : "text/plain";
    }

    private static boolean isToken(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "!#$%&'*+-.^_`|~".indexOf(c) >= 0)) return false;
        }
        return true;
    }

    /// `CGI.unescapeHTML`
    public static String cgiUnescapeHtml(String s) {
        if (s.indexOf('&') < 0) return s;
        var out = new StringBuilder(s.length());
        int i = 0;
        next:
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.append(c);
                i++;
                continue;
            }
            for (String[] pair : NAMED_ENTITIES) {
                if (s.startsWith(pair[0], i)) {
                    out.append(pair[1]);
                    i += pair[0].length();
                    continue next;
                }
            }
            // `&#digits;` or `&#xhex;`: the digits run up to the `;` (a digit is never a `;`).
            Integer code = null;
            int end = i;
            if (s.startsWith("#", i + 1)) {
                boolean hex = s.startsWith("x", i + 2) || s.startsWith("X", i + 2);
                int radix = hex ? 16 : 10;
                int start = i + (hex ? 3 : 2);
                end = start;
                while (end < s.length() && Character.digit(s.charAt(end), radix) >= 0) end++;
                if (end < s.length() && s.charAt(end) == ';') code = numericReference(s.substring(start, end), radix);
            }
            if (code != null) {
                out.appendCodePoint(code);
                i = end + 1;
            } else {
                out.append('&');
                i++;
            }
        }
        return out.toString();
    }

    private static final String[][] NAMED_ENTITIES = {{"&apos;", "'"}, {"&amp;", "&"}, {"&quot;", "\""}, {"&gt;", ">"}, {"&lt;", "<"}};

    private static Integer numericReference(String digits, int radix) {
        if (digits.isEmpty()) return null;
        for (int i = 0; i < digits.length(); i++) if (Character.digit(digits.charAt(i), radix) < 0) return null;
        String significant = digits.replaceFirst("^0+", "");
        if (significant.length() > (radix == 16 ? 6 : 7)) return null;
        int code = significant.isEmpty() ? 0 : Integer.parseInt(significant, radix);
        return code < 0x10ffff && Character.isValidCodePoint(code) ? code : null;
    }

    /// Loofah's numeric character reference decoding (with or without the semicolon).
    private static String decodeNumericCharacterReferences(String s) {
        if (s.indexOf("&#") < 0) return s;
        var out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.startsWith("&#", i)) {
                int start = i + 2;
                boolean hex = start < s.length() && (s.charAt(start) == 'x' || s.charAt(start) == 'X');
                int digitsStart = hex ? start + 1 : start;
                int end = digitsStart;
                while (end < s.length() && Character.digit(s.charAt(end), hex ? 16 : 10) >= 0) end++;
                if (end > digitsStart) {
                    String digits = s.substring(digitsStart, end);
                    int fullEnd = end < s.length() && s.charAt(end) == ';' ? end + 1 : end;
                    Integer code = numericReference(digits, hex ? 16 : 10);
                    if (code != null) {
                        out.appendCodePoint(code);
                        i = fullEnd;
                        continue;
                    }
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }
}
