package campfire.web;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The app's own response compression (`Rack::Deflater`, which the Rails app installs around
/// everything): every response with a body is gzipped for a client that takes gzip, whatever its
/// size or type, and every one says it varies on `Accept-Encoding` either way. A client that
/// refuses both gzip and identity gets a plain-text 406.
@NullMarked
final class ContentEncoding {
    private ContentEncoding() {}

    private static final Pattern NO_TRANSFORM = Pattern.compile("(?<![A-Za-z0-9_])no-transform(?![A-Za-z0-9_])");
    private static final Pattern IDENTITY = Pattern.compile("(?<![A-Za-z0-9_])identity(?![A-Za-z0-9_])");
    private static final Pattern QUALITY = Pattern.compile("^\\d*(?:\\.\\d+)?");
    private static final List<String> AVAILABLE = List.of("gzip", "identity");

    /// Not for statuses without a body (1xx, 204, 304), `no-transform`, a body already encoded,
    /// or an empty body whose length the app set itself (files and error pages). Other empty
    /// bodies, redirects among them, are gzipped too.
    static boolean shouldDeflate(int status, @Nullable String cacheControl, @Nullable String contentEncoding, boolean emptyWithAppSetLength) {
        if (status < 200 || status == 204 || status == 304) return false;
        if (cacheControl != null && NO_TRANSFORM.matcher(cacheControl).find()) return false;
        if (contentEncoding != null && !IDENTITY.matcher(contentEncoding).find()) return false;
        return !emptyWithAppSetLength;
    }

    /// The `Vary` to send instead of `existing` (every value of the header), or null to leave it:
    /// its names plus `Accept-Encoding`, comma-joined without spaces, unless it already varies on
    /// that or on everything.
    static @Nullable String vary(List<String> existing) {
        var names = new ArrayList<String>();
        for (String value : existing) for (String name : value.split(",")) names.add(name.strip());
        for (String name : names) if (name.equals("*") || name.equalsIgnoreCase("accept-encoding")) return null;
        names.add("Accept-Encoding");
        return String.join(",", names);
    }

    /// Answers by header value: browsers send the same few `Accept-Encoding`s on every request. ""
    /// stands for none acceptable. Cleared when full, as the values come from clients.
    private static final ConcurrentHashMap<String, String> NEGOTIATED = new ConcurrentHashMap<>();

    /// "gzip" or "identity", or null when the client accepts neither.
    static @Nullable String negotiate(@Nullable String acceptEncoding) {
        String header = acceptEncoding == null ? "" : acceptEncoding;
        String answer = NEGOTIATED.get(header);
        if (answer == null) {
            if (NEGOTIATED.size() >= 1024) NEGOTIATED.clear();
            answer = Objects.requireNonNullElse(choose(header), "");
            NEGOTIATED.put(header, answer);
        }
        return answer.isEmpty() ? null : answer;
    }

    private static @Nullable String choose(String acceptEncoding) {
        record Accepted(String coding, double quality, int preference) {}
        var accepted = new ArrayList<Accepted>();
        List<String[]> parsed = parse(acceptEncoding);
        List<String[]> considered = parsed.subList(0, Math.min(16, parsed.size()));
        boolean wildcardSeen = false;
        for (String[] entry : considered) {
            String coding = entry[0];
            double q = Double.parseDouble(entry[1]);
            int preference = AVAILABLE.indexOf(coding) < 0 ? AVAILABLE.size() : AVAILABLE.indexOf(coding);
            if (coding.equals("*")) {
                if (wildcardSeen) continue;
                for (String other : AVAILABLE) {
                    if (considered.stream().noneMatch(e -> e[0].equals(other))) accepted.add(new Accepted(other, q, preference));
                }
                wildcardSeen = true;
            } else {
                accepted.add(new Accepted(coding, q, preference));
            }
        }
        var candidates = new ArrayList<>(accepted.stream()
            .sorted(Comparator.comparingDouble(Accepted::quality).reversed().thenComparingInt(Accepted::preference))
            .map(Accepted::coding).toList());
        if (!candidates.contains("identity")) candidates.add("identity");
        for (Accepted a : accepted) if (a.quality() == 0.0) candidates.removeIf(c -> c.equals(a.coding()));
        for (String candidate : candidates) if (AVAILABLE.contains(candidate)) return candidate;
        return null;
    }

    /// The 406 body when no acceptable encoding is left, naming the path and query requested.
    static byte[] notAcceptable(String pathAndQuery) {
        return ("An acceptable encoding for the requested resource " + pathAndQuery + " could not be found.").getBytes(StandardCharsets.UTF_8);
    }

    /// The gzip header's mtime: the response's `Last-Modified` in epoch seconds, else 0.
    static long mtime(@Nullable String lastModified) {
        if (lastModified == null) return 0;
        try {
            return ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() & 0xffffffffL;
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /// Each coding with its quality: 1 without a `q=`, else the leading number of its value as
    /// Ruby's `to_f` reads it (`0.5.1` is 0.5, `.` is 0); `Q=` isn't a quality.
    private static List<String[]> parse(String header) {
        var out = new ArrayList<String[]>();
        for (String raw : header.split(",")) {
            String part = raw.strip();
            if (part.isEmpty()) continue;
            int semi = part.indexOf(';');
            String coding = (semi < 0 ? part : part.substring(0, semi)).strip();
            double quality = 1.0;
            if (semi >= 0) {
                String parameters = part.substring(semi + 1).strip();
                if (parameters.startsWith("q=")) {
                    String value = parameters.substring(2);
                    int end = 0;
                    while (end < value.length() && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.')) end++;
                    String digits = value.substring(0, end);
                    if (!digits.isEmpty()) quality = toF(digits);
                }
            }
            out.add(new String[] {coding, Double.toString(quality)});
        }
        return out;
    }

    private static double toF(String digits) {
        Matcher m = QUALITY.matcher(digits);
        String number = m.find() ? m.group() : "";
        return number.isEmpty() ? 0.0 : Double.parseDouble(number.startsWith(".") ? "0" + number : number);
    }
}
