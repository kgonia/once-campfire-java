package campfire.richtext;

import java.util.Set;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/// HTML5 fragments (jsoup's tree builder, like Nokogiri's Gumbo) and the HTML serialization
/// algorithm Nokogiri follows, so markup round-trips the way it does in Rails.
public final class Html {
    private Html() {}

    static final Set<String> VOID_ELEMENTS = Set.of("area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "img",
        "input", "keygen", "link", "meta", "param", "source", "track", "wbr");
    private static final Set<String> RAW_TEXT = Set.of("style", "script", "xmp", "iframe", "noembed", "noframes", "plaintext", "noscript");

    /// Parses `html` as a fragment in a body context; the fragment's nodes are the returned
    /// element's children.
    public static Element parseFragment(String html) {
        Document document = Parser.parseBodyFragment(preprocess(html == null ? "" : html), "");
        return document.body();
    }

    /// The HTML5 input stream preprocessing Gumbo does: CRLF and CR become LF, NUL becomes U+FFFD.
    static String preprocess(String html) {
        if (html.indexOf('\r') < 0 && html.indexOf('\0') < 0) return html;
        return html.replace("\r\n", "\n").replace('\r', '\n').replace('\0', '�');
    }

    public static String innerHtml(Node node) {
        var out = new StringBuilder();
        for (Node child : node.childNodes()) write(out, child, false);
        return out.toString();
    }

    public static String outerHtml(Node node) {
        var out = new StringBuilder();
        write(out, node, false);
        return out.toString();
    }

    /// Serialization with `<` and `>` escaped in attribute values too, as auto_link needs.
    public static String innerHtmlEscapingAttributeBrackets(Node node) {
        var out = new StringBuilder();
        for (Node child : node.childNodes()) write(out, child, true);
        return out.toString();
    }

    private static void write(StringBuilder out, Node node, boolean escapeAttributeBrackets) {
        switch (node) {
            case TextNode text -> {
                Node parent = text.parentNode();
                if (parent instanceof Element e && RAW_TEXT.contains(e.normalName())) out.append(text.getWholeText());
                else escapeText(out, text.getWholeText());
            }
            case DataNode data -> out.append(data.getWholeData());
            case Comment comment -> out.append("<!--").append(comment.getData()).append("-->");
            case Element element -> {
                String name = element.tagName();
                out.append('<').append(name);
                for (Attribute attribute : element.attributes()) {
                    out.append(' ').append(attribute.getKey());
                    out.append("=\"");
                    escapeAttribute(out, attribute.getValue(), escapeAttributeBrackets);
                    out.append('"');
                }
                out.append('>');
                if (VOID_ELEMENTS.contains(element.normalName())) return;
                for (Node child : element.childNodes()) write(out, child, escapeAttributeBrackets);
                out.append("</").append(name).append('>');
            }
            default -> out.append(node.outerHtml());
        }
    }

    static void escapeText(StringBuilder out, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case ' ' -> out.append("&nbsp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
    }

    static void escapeAttribute(StringBuilder out, String s, boolean brackets) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case ' ' -> out.append("&nbsp;");
                case '"' -> out.append("&quot;");
                case '<' -> out.append(brackets ? "&lt;" : "<");
                case '>' -> out.append(brackets ? "&gt;" : ">");
                default -> out.append(c);
            }
        }
    }

    /// `ERB::Util.html_escape`
    public static String escape(String s) {
        if (s == null) return "";
        int i = 0;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' || c == '<' || c == '>' || c == '"' || c == '\'') break;
        }
        if (i == s.length()) return s;
        var out = new StringBuilder(s.length() + 16).append(s, 0, i);
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /// Ruby's `String#strip` (whitespace and NUL).
    public static String strip(String s) {
        int start = 0, end = s.length();
        while (start < end && isRubySpace(s.charAt(start))) start++;
        while (end > start && (isRubySpace(s.charAt(end - 1)) || s.charAt(end - 1) == '\0')) end--;
        return s.substring(start, end);
    }

    static boolean isRubySpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000b' || c == '\f' || c == '\r' || c == '\0';
    }

    /// `String#blank?`: empty or only whitespace.
    public static boolean isBlank(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
            i += Character.charCount(c);
        }
        return true;
    }

    /// `String#truncate(length, omission:)`
    public static String truncate(String text, int length, String omission) {
        if (text.codePointCount(0, text.length()) <= length) return text;
        int keep = Math.max(0, length - omission.codePointCount(0, omission.length()));
        return text.substring(0, text.offsetByCodePoints(0, keep)) + omission;
    }
}
