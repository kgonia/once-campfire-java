package campfire.web;

import io.vertx.core.MultiMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32C;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The proxy's compression (Thruster's gzhttp: 1 KB minimum, gzip level 6, BREACH padding in the
/// gzip comment). The app has already gzipped almost every response by then, and an encoded body
/// is left alone; what it adds is `Vary: Accept-Encoding` on every response, and compression of
/// the few unencoded bodies a client still wants compressed. Clients preferring zstd get those
/// uncompressed.
@NullMarked
final class FrontCompression {
    static final int MIN_SIZE = 1024;
    /// The padding is derived from (at most) the first 64 KB of the body.
    private static final int JITTER_SAMPLE = 64 << 10;

    private static final List<String> EXCLUDED_CONTAINING = List.of("compress", "zip", "snappy", "lzma", "xz", "zstd", "brotli", "stuffit");
    private static final List<String> EXCLUDED_PREFIXES = List.of("video/", "audio/", "image/jp", "image/jpeg", "image/jpg", "image/png",
        "image/apng", "image/webp", "image/gif", "image/avif", "image/heic", "image/heif", "image/jxl");

    enum Encoding { NONE, GZIP, ZSTD }

    /// `strings.Repeat("Padding-", 1+(n/8))[:n+1]`, or empty without jitter.
    private final String padding;
    private final boolean disabledOnAuth;

    FrontCompression(int jitter, boolean disabledOnAuth) {
        this.padding = jitter > 0 ? "Padding-".repeat(1 + jitter / 8).substring(0, jitter + 1) : "";
        this.disabledOnAuth = disabledOnAuth;
    }

    /// Never for HEAD; zstd when its quality is at least gzip's.
    static Encoding select(boolean head, @Nullable String acceptEncoding) {
        if (head || acceptEncoding == null || acceptEncoding.isEmpty()) return Encoding.NONE;
        double gzip = quality(acceptEncoding, "gzip");
        double zstd = quality(acceptEncoding, "zstd");
        if (gzip <= 0 && zstd <= 0) return Encoding.NONE;
        if (zstd <= 0) return Encoding.GZIP;
        if (gzip <= 0) return Encoding.ZSTD;
        return gzip > zstd ? Encoding.GZIP : Encoding.ZSTD;
    }

    /// The `Vary: Accept-Encoding` gzhttp sets before the app runs. Responses the cache handled
    /// copy their own `Vary` over it; ones that bypassed it add theirs after it, so the header can
    /// repeat.
    static void addVary(MultiMap headers, boolean bypassed) {
        List<String> existing = headers.getAll("vary");
        if (!bypassed && !existing.isEmpty()) return;
        headers.remove("vary");
        headers.add("Vary", "Accept-Encoding");
        for (String value : existing) headers.add("Vary", value);
    }

    /// Whether to compress an unencoded body: the client wants it, nothing vetoes it (cookies and
    /// private responses, when compression is off for authenticated traffic), and it's at least
    /// 1 KB of a compressible type.
    boolean wanted(Encoding encoding, @Nullable String requestCookie, @Nullable String authorization, @Nullable String csrfToken,
                   @Nullable String setCookie, @Nullable String cacheControl, @Nullable String vary, @Nullable String contentEncoding,
                   @Nullable String contentRange, @Nullable String contentType, int length) {
        if (encoding != Encoding.GZIP) return false;
        if (disabledOnAuth && (present(requestCookie) || present(authorization) || present(csrfToken) || userSpecific(setCookie, cacheControl, vary))) {
            return false;
        }
        if (present(contentEncoding) || present(contentRange)) return false;
        return length >= MIN_SIZE && compressible(contentType == null ? "" : contentType);
    }

    /// The padding for this body: a prefix (1 to n bytes long) picked by the CRC-32C of its start.
    @Nullable String jitter(byte[] body) {
        if (padding.isEmpty()) return null;
        var crc = new CRC32C();
        crc.update(body, 0, Math.min(body.length, JITTER_SAMPLE));
        int rng = Integer.rotateLeft((int) crc.getValue(), 19) ^ 0xab0755de;
        int length = 1 + (int) (Integer.toUnsignedLong(rng) % (padding.length() - 1));
        return padding.substring(0, length);
    }

    /// Thruster's filter: gzhttp's default, minus images that are compressed already.
    static boolean compressible(String contentType) {
        String type = contentType.strip().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) return true;
        for (String s : EXCLUDED_CONTAINING) if (type.contains(s)) return false;
        for (String p : EXCLUDED_PREFIXES) if (type.startsWith(p)) return false;
        return true;
    }

    private static boolean userSpecific(@Nullable String setCookie, @Nullable String cacheControl, @Nullable String vary) {
        if (present(setCookie)) return true;
        if (cacheControl != null) {
            for (String directive : cacheControl.toLowerCase(Locale.ROOT).split(",")) {
                String name = directive.strip().split("=", 2)[0];
                if (name.equals("private") || name.equals("no-store")) return true;
            }
        }
        if (vary != null) for (String token : vary.split(",")) if (token.strip().equalsIgnoreCase("cookie")) return true;
        return false;
    }

    /// The first listed coding's quality (1 without a `q`), else 0.
    private static double quality(String header, String encoding) {
        for (String part : header.strip().split(",")) {
            String[] pieces = part.split(";");
            if (!pieces[0].strip().toLowerCase(Locale.ROOT).equals(encoding)) continue;
            double q = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String piece = pieces[i].strip();
                if (piece.startsWith("q=")) {
                    try {
                        q = Math.clamp(Double.parseDouble(piece.substring(2)), 0.0, 1.0);
                    } catch (NumberFormatException e) {
                        q = 0.0;
                    }
                }
            }
            return q;
        }
        return 0.0;
    }

    private static boolean present(@Nullable String value) {
        return value != null && !value.isEmpty();
    }
}
