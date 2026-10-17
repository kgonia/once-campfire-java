package campfire.web;

import campfire.rails.HttpFormats;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Rack's nested parameters: `a[b]=1` is `{a: {b: "1"}}`, `a[]=1&a[]=2` is `{a: ["1", "2"]}`.
/// Values are `String`, `Upload`, `List` or `Map`.
public final class Params {
    private final Map<String, Object> values = new LinkedHashMap<>();

    /// An uploaded file from a multipart form.
    public record Upload(String filename, String contentType, java.nio.file.Path path, long size) {}

    public void addQuery(@Nullable String query) {
        if (query == null || query.isEmpty()) return;
        // Rack 3 splits on `/& */` only (`;` is no longer a separator).
        for (String pair : query.split("& *")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = HttpFormats.unescape(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : HttpFormats.unescape(pair.substring(eq + 1));
            put(key, value);
        }
    }

    /// Sets a top-level parameter as is (a JSON body's values: also null, numbers and booleans).
    public void putValue(String key, @Nullable Object value) {
        values.put(key, value);
    }

    /// Adds `key` (`a[b][]`) with `value`, as `Rack::QueryParser#normalize_params` does.
    @SuppressWarnings("unchecked")
    public void put(String key, Object value) {
        if (key.isEmpty()) return;
        int bracket = key.indexOf('[');
        String head = bracket <= 0 ? key : key.substring(0, bracket);
        String rest = bracket <= 0 ? "" : key.substring(bracket);
        if (bracket == 0) {
            head = key;
            rest = "";
        }
        normalize(values, head, rest, value);
    }

    @SuppressWarnings("unchecked")
    private static void normalize(Map<String, Object> target, String name, String rest, Object value) {
        if (rest.isEmpty()) {
            target.put(name, value);
            return;
        }
        if (rest.startsWith("[]")) {
            String after = rest.substring(2);
            Object existing = target.get(name);
            List<Object> list = existing instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
            target.put(name, list);
            if (after.isEmpty()) {
                list.add(value);
            } else if (after.startsWith("[")) {
                int close = after.indexOf(']');
                if (close < 0) return;
                String child = after.substring(1, close);
                String childRest = after.substring(close + 1);
                Map<String, Object> last = !list.isEmpty() && list.getLast() instanceof Map<?, ?> m && !containsPath(m, child, childRest)
                    ? (Map<String, Object>) m : null;
                if (last == null) {
                    last = new LinkedHashMap<>();
                    list.add(last);
                }
                normalize(last, child, childRest, value);
            }
            return;
        }
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                target.put(name + rest, value);
                return;
            }
            String child = rest.substring(1, close);
            String childRest = rest.substring(close + 1);
            Object existing = target.get(name);
            Map<String, Object> map = existing instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
            target.put(name, map);
            normalize(map, child, childRest, value);
            return;
        }
        target.put(name + rest, value);
    }

    private static boolean containsPath(Map<?, ?> map, String child, String rest) {
        return map.containsKey(child) && rest.isEmpty();
    }

    public @Nullable Object get(String... path) {
        Object current = values;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(key);
        }
        return current;
    }

    /// The string at `path`, or null (also for nested values).
    public @Nullable String string(String... path) {
        return get(path) instanceof String s ? s : null;
    }

    /// `params[...].presence`
    public @Nullable String present(String... path) {
        String s = string(path);
        return s == null || s.isBlank() ? null : s;
    }

    public @Nullable Upload upload(String... path) {
        return get(path) instanceof Upload u ? u : null;
    }

    public boolean has(String... path) {
        return get(path) != null;
    }

    public List<String> strings(String... path) {
        Object value = get(path);
        var out = new ArrayList<String>();
        if (value instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s) out.add(s);
        } else if (value instanceof String s) {
            out.add(s);
        }
        return out;
    }

    /// Ids from `user_ids[]`, ignoring what isn't a number.
    public List<Long> ids(String... path) {
        var out = new ArrayList<Long>();
        for (String s : strings(path)) {
            Long id = parseId(s);
            if (id != null && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /// Active Record's integer casting of an id parameter (`"12abc"` is 12), or null.
    public static @Nullable Long parseId(@Nullable String s) {
        if (s == null) return null;
        s = s.strip();
        int end = 0;
        if (end < s.length() && (s.charAt(end) == '-' || s.charAt(end) == '+')) end++;
        int digits = end;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == digits) return null;
        try {
            return Long.parseLong(s.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
