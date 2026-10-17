package campfire.richtext;

import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/// `ActionText::PlainTextConversion`: block elements end in blank lines, list items get bullets
/// or numbers, block quotes curly quotes.
final class PlainText {
    private PlainText() {}

    static String of(Node node) {
        return chompNewlines(plainTextFor(node));
    }

    private static String childValues(Node node) {
        var out = new StringBuilder();
        for (Node child : node.childNodes()) out.append(plainTextFor(child));
        return out.toString();
    }

    private static String plainTextFor(Node node) {
        if (node instanceof TextNode text) return chompNewlines(text.getWholeText());
        if (node instanceof DataNode) return "";
        if (!(node instanceof Element element)) return childValues(node);
        return switch (element.normalName()) {
            case "script", "style", "unsupported" -> "";
            case "h1", "p" -> block(childValues(element));
            case "ul", "ol" -> {
                String text = block(childValues(element));
                yield listDepth(element) > 0 ? "\n" + text : text;
            }
            case "br" -> "\n";
            case "div" -> chompNewlines(childValues(element)) + "\n";
            case "figcaption" -> "[" + chompNewlines(childValues(element)) + "]";
            case "blockquote" -> {
                String text = block(childValues(element));
                if (Html.isBlank(text)) yield "“”";
                int last = text.length() - 1;
                while (last >= 0 && Html.isRubySpace(text.charAt(last)) && text.charAt(last) != '\0') last--;
                if (last >= 0) text = text.substring(0, last + 1) + "”" + text.substring(last + 1);
                int first = 0;
                while (first < text.length() && Html.isRubySpace(text.charAt(first)) && text.charAt(first) != '\0') first++;
                if (first < text.length()) text = text.substring(0, first) + "“" + text.substring(first);
                yield text;
            }
            case "li" -> {
                String bullet = bulletFor(element);
                String text = chompNewlines(childValues(element));
                int depth = listDepth(element);
                yield (depth > 1 ? "  ".repeat(depth - 1) : "") + bullet + " " + text + "\n";
            }
            default -> childValues(element);
        };
    }

    private static String block(String childValues) {
        return chompNewlines(childValues) + "\n\n";
    }

    private static boolean isList(Element e) {
        return e.normalName().equals("ul") || e.normalName().equals("ol");
    }

    private static int listDepth(Element element) {
        int depth = 0;
        for (Element a = element.parent(); a != null; a = a.parent()) if (isList(a)) depth++;
        return depth;
    }

    private static String bulletFor(Element li) {
        Element list = null;
        for (Element a = li.parent(); a != null; a = a.parent()) {
            if (isList(a)) {
                list = a;
                break;
            }
        }
        if (list != null && list.normalName().equals("ol")) {
            Element parent = li.parent();
            int index = parent == null ? 0 : parent.children().indexOf(li);
            return (Math.max(index, 0) + 1) + ".";
        }
        return "•";
    }

    /// Ruby's `chomp` repeated: trailing `\n` and `\r\n` removed.
    static String chompNewlines(String s) {
        int end = s.length();
        while (true) {
            if (end >= 2 && s.charAt(end - 2) == '\r' && s.charAt(end - 1) == '\n') end -= 2;
            else if (end >= 1 && s.charAt(end - 1) == '\n') end -= 1;
            else return s.substring(0, end);
        }
    }
}
