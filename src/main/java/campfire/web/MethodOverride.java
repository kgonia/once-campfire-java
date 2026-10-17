package campfire.web;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/// The `_method` field of a form body, found before the form itself is parsed (see
/// `AccessFilter.methodOverride`).
final class MethodOverride {
    private MethodOverride() {}

    static @Nullable String fromForm(byte[] body) {
        var params = new Params();
        params.addQuery(new String(body, StandardCharsets.UTF_8));
        return params.string("_method");
    }

    /// The first `_method` part in what's been read of a multipart body.
    static @Nullable String fromMultipart(byte[] start, @Nullable String boundary) {
        if (boundary == null || boundary.isEmpty()) return null;
        // ISO-8859-1 keeps byte offsets, and the part headers are ASCII.
        String text = new String(start, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        int at = text.indexOf(delimiter);
        while (at >= 0) {
            int headersStart = at + delimiter.length() + 2;
            int headersEnd = text.indexOf("\r\n\r\n", headersStart);
            if (headersEnd < 0) return null;
            int next = text.indexOf("\r\n" + delimiter, headersEnd);
            if (next < 0) return null;
            String headers = text.substring(headersStart, headersEnd).toLowerCase(Locale.ROOT);
            if (headers.contains("name=\"_method\"") && !headers.contains("filename=")) {
                return new String(start, headersEnd + 4, next - headersEnd - 4, StandardCharsets.UTF_8);
            }
            at = next + 2;
        }
        return null;
    }
}
