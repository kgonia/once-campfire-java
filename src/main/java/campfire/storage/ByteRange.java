package campfire.storage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// The inclusive span a `Range: bytes=` header asks for in a file of a given size. Only single
/// ranges are served: several, or a header that doesn't parse, get the whole file, which HTTP
/// allows a server to answer with.
record ByteRange(long first, long last) {
    private static final Pattern SPEC = Pattern.compile("bytes=\\s*(\\d{0,18})\\s*-\\s*(\\d{0,18})", Pattern.CASE_INSENSITIVE);

    /// Null for the whole file. Like Rack, an empty file is always sent whole.
    static @Nullable ByteRange parse(@Nullable String header, long size) {
        if (header == null || size <= 0) return null;
        Matcher spec = SPEC.matcher(header.strip());
        if (!spec.matches()) return null;
        String from = spec.group(1);
        String to = spec.group(2);
        if (from.isEmpty()) return to.isEmpty() ? null : new ByteRange(Math.max(0, size - Long.parseLong(to)), size - 1);
        long first = Long.parseLong(from);
        if (to.isEmpty()) return new ByteRange(first, size - 1);
        long last = Long.parseLong(to);
        return last < first ? null : new ByteRange(first, Math.min(last, size - 1));
    }

    /// False when the range starts past the end (or is the last zero bytes): a 416.
    boolean satisfiable() {
        return first <= last;
    }

    long length() {
        return last - first + 1;
    }

    String contentRange(long size) {
        return "bytes " + first + "-" + last + "/" + size;
    }
}
