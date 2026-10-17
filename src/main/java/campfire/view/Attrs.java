package campfire.view;

import campfire.rails.Json;
import campfire.richtext.Html;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Tag attributes rendered as ActionView's `tag_options` does: nil and false are left out,
/// boolean attributes render as `name="name"`, `data`/`aria` maps become prefixed attributes
/// with dasherized keys, class lists are joined.
public final class Attrs {
    private static final Set<String> BOOLEAN = Set.of("allowfullscreen", "allowpaymentrequest", "async", "autofocus", "autoplay", "checked",
        "compact", "controls", "declare", "default", "defaultchecked", "defaultmuted", "defaultselected", "defer", "disabled", "enabled",
        "formnovalidate", "hidden", "indeterminate", "inert", "ismap", "itemscope", "loop", "multiple", "muted", "nohref", "nomodule",
        "noresize", "noshade", "novalidate", "nowrap", "open", "pauseonexit", "playsinline", "readonly", "required", "reversed", "scoped",
        "seamless", "selected", "sortable", "truespeed", "typemustmatch", "visible");

    final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

    public static Attrs of(Object... pairs) {
        var attrs = new Attrs();
        for (int i = 0; i + 1 < pairs.length; i += 2) attrs.set((String) pairs[i], pairs[i + 1]);
        return attrs;
    }

    public Attrs set(String name, Object value) {
        values.put(name, value);
        return this;
    }

    /// Merges into a `data` map: `data("controller", "x")` renders `data-controller="x"`.
    @SuppressWarnings("unchecked")
    public Attrs data(Object... pairs) {
        var data = (Map<String, Object>) values.computeIfAbsent("data", k -> new LinkedHashMap<String, Object>());
        for (int i = 0; i + 1 < pairs.length; i += 2) data.put((String) pairs[i], pairs[i + 1]);
        return this;
    }

    @SuppressWarnings("unchecked")
    public Attrs aria(Object... pairs) {
        var aria = (Map<String, Object>) values.computeIfAbsent("aria", k -> new LinkedHashMap<String, Object>());
        for (int i = 0; i + 1 < pairs.length; i += 2) aria.put((String) pairs[i], pairs[i + 1]);
        return this;
    }

    public Object get(String name) {
        return values.get(name);
    }

    public Object remove(String name) {
        return values.remove(name);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    public Attrs copy() {
        var copy = new Attrs();
        for (var entry : values.entrySet()) {
            Object v = entry.getValue();
            copy.values.put(entry.getKey(), v instanceof Map<?, ?> m ? new LinkedHashMap<>(m) : v);
        }
        return copy;
    }

    public Attrs merge(Attrs other) {
        if (other != null) for (var entry : other.values.entrySet()) values.put(entry.getKey(), entry.getValue());
        return this;
    }

    /// ` name="value"` for each attribute.
    public void appendTo(StringBuilder out) {
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ((key.equals("data") || key.equals("aria")) && value instanceof Map<?, ?> map) {
                for (var nested : map.entrySet()) {
                    Object v = nested.getValue();
                    if (v == null) continue;
                    String name = key + "-" + nested.getKey().toString().replace('_', '-');
                    String rendered = v instanceof String s ? s : v instanceof Safe safe ? safe.toString()
                        : v instanceof Map<?, ?> || v instanceof List<?> ? Json.generate(v) : String.valueOf(v);
                    if (key.equals("aria") && v instanceof List<?> l) rendered = String.join(" ", l.stream().map(String::valueOf).toList());
                    if (v instanceof Safe) appendRaw(out, name, rendered);
                    else append(out, name, rendered);
                }
                continue;
            }
            if (value == null || Boolean.FALSE.equals(value)) continue;
            if (Boolean.TRUE.equals(value) && BOOLEAN.contains(key)) {
                append(out, key, key);
                continue;
            }
            if (key.equals("class")) {
                String classes = classNames(value);
                if (classes != null) append(out, key, classes);
                continue;
            }
            if (value instanceof Safe s) appendRaw(out, key, s.toString());
            else append(out, key, String.valueOf(value));
        }
    }

    /// `html_safe` values go in as they are, as `tag_option` does.
    private static void appendRaw(StringBuilder out, String name, String value) {
        out.append(' ').append(name).append("=\"").append(value).append('"');
    }

    private static void append(StringBuilder out, String name, String value) {
        out.append(' ').append(name).append("=\"").append(Html.escape(value)).append('"');
    }

    /// `class: ["a", { "b": true }]` is `"a b"`.
    static String classNames(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof List<?> list) {
            var names = new ArrayList<String>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    for (var e : m.entrySet()) if (Boolean.TRUE.equals(e.getValue())) names.add(e.getKey().toString());
                } else if (o != null && !o.toString().isEmpty()) {
                    names.add(o.toString());
                }
            }
            return String.join(" ", names);
        }
        return value == null ? null : value.toString();
    }

    @Override
    public String toString() {
        var out = new StringBuilder();
        appendTo(out);
        return out.toString();
    }
}
