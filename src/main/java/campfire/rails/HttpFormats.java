package campfire.rails;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

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

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /// The ASCII `filename` fallback: accents dropped (`é` is `e`), `?` for any other non-ASCII
    /// character. Rails uses `I18n.transliterate`, which also spells out `ß`, `æ` and the like;
    /// clients read the exact name from `filename*`.
    static String transliterate(String s) {
        String decomposed = MARKS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        var out = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(c -> out.append(c < 128 ? (char) c : '?'));
        return out.toString();
    }
}
