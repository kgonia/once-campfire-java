package campfire.view;

import campfire.richtext.Html;

/// ActionView's tag helpers.
public final class Tag {
    private Tag() {}

    /// `tag(name, options)`: a void element, `<name ... />`.
    public static Safe empty(String name, Attrs attrs) {
        var out = new StringBuilder(64).append('<').append(name);
        if (attrs != null) attrs.appendTo(out);
        return Safe.of(out.append(" />").toString());
    }

    private static final java.util.Set<String> VOID = java.util.Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "keygen",
        "link", "meta", "param", "source", "track", "wbr");

    /// `tag.name(...)`: `<name ...>`, closed right away unless it's a void element.
    public static Safe open(String name, Attrs attrs) {
        var out = new StringBuilder(64).append('<').append(name);
        if (attrs != null) attrs.appendTo(out);
        out.append('>');
        if (!VOID.contains(name)) out.append("</").append(name).append('>');
        return Safe.of(out.toString());
    }

    /// `content_tag(name, content, options)`: content is escaped unless it's {@link Safe}.
    public static Safe content(String name, Attrs attrs, Object content) {
        var out = new StringBuilder(128).append('<').append(name);
        if (attrs != null) attrs.appendTo(out);
        out.append('>');
        if (name.equals("textarea")) out.append('\n');
        out.append(Safe.from(content));
        return Safe.of(out.append("</").append(name).append('>').toString());
    }
}
