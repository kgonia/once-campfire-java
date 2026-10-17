package campfire.rails;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/// Rack's cookie value escaping and `ActionDispatch::Http::ContentDisposition.format`.
public final class HttpFormats {
    private HttpFormats() {}

    /// `Rack::Utils.escape` (`URI.encode_www_form_component`): `*-._` and alphanumerics stay,
    /// space becomes `+`, the rest is `%XX`.
    public static String escape(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /// `unescape(value) rescue value`: `+` is a space, `%XX` decoded, a malformed escape leaves
    /// the value untouched.
    public static String unescape(String wire) {
        try {
            return URLDecoder.decode(wire, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return wire;
        }
    }

    /// `ContentDisposition.format(disposition:, filename:)`.
    public static String contentDisposition(String disposition, String filename) {
        return disposition + "; filename=\"" + percentEscape(transliterate(filename), false) + "\"; filename*=UTF-8''"
            + percentEscape(filename, true);
    }

    private static String percentEscape(String s, boolean rfc5987) {
        var out = new StringBuilder(s.length());
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$+.^_`|~-".indexOf(c) >= 0 || (rfc5987 ? c == '&' : c == ' ');
            if (keep) out.append((char) c);
            else out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
        }
        return out.toString();
    }

    private static final Map<Integer, String> APPROXIMATIONS = loadApproximations();

    private static Map<Integer, String> loadApproximations() {
        var map = new HashMap<Integer, String>();
        try (var in = HttpFormats.class.getResourceAsStream("/campfire/rails/approximations.tsv");
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                map.put(line.codePointAt(0), line.substring(tab + 1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return map;
    }

    /// `I18n.transliterate` with the default approximations, and `?` for anything else non-ASCII.
    public static String transliterate(String s) {
        var out = new StringBuilder(s.length());
        s.codePoints().forEach(c -> {
            if (c < 128) out.appendCodePoint(c);
            else out.append(APPROXIMATIONS.getOrDefault(c, "?"));
        });
        return out.toString();
    }
}
