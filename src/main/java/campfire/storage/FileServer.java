package campfire.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// `ActiveStorage::FileServer#serve_file` on top of `Rack::Files#serving` (rack 3.2): conditional
/// GET on mtime, single and multipart byte ranges, and 416s.
///
/// Only whole files are streamed, so a range's bytes are read into the body. A single
/// range is cut to {@link #MAX_RANGE_BYTES} (clients ask again for the rest: `Content-Range` says
/// what was sent), and multipart ranges past that limit are ignored (the whole file is served),
/// which HTTP allows.
public final class FileServer {
    private FileServer() {}

    /// What to send: a status, headers, and either a body or a whole file to stream.
    public static final class Reply {
        public int status = 200;
        public final java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        public byte[] body = new byte[0];
        public Path file;

        void header(String name, String value) {
            headers.put(name, value);
        }

        void removeHeader(String name) {
            headers.remove(name);
        }

        void body(byte[] bytes, String contentType) {
            body = bytes;
            headers.put("Content-Type", contentType);
        }
    }

    public static final String MULTIPART_BOUNDARY = "AaB03x";
    static final long MAX_RANGE_BYTES = 8L * 1024 * 1024;
    private static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    /// Fills `response` for `serve_file(path, content_type:, disposition:)`. Throws
    /// `NoSuchFileException` (wrapped) when the file is missing, for the caller's 404.
    public static void serve(Reply response, String method, String rangeHeader, String ifModifiedSince, Path path, String contentType,
                             String disposition) throws NoSuchFileException {
        serving(response, method, rangeHeader, ifModifiedSince, path);
        if (response.status == 416) response.removeHeader("X-Cascade");
        response.header("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        response.header("Content-Disposition", disposition == null ? "attachment" : disposition);
    }

    private static void serving(Reply response, String method, String rangeHeader, String ifModifiedSince, Path path)
        throws NoSuchFileException {
        long size;
        String lastModified;
        try {
            size = Files.size(path);
            lastModified = HTTP_DATE.format(Files.getLastModifiedTime(path).toInstant());
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (lastModified.equals(ifModifiedSince)) {
            response.status = 304;
            response.body = new byte[0];
            return;
        }
        response.header("Last-Modified", lastModified);
        // Disk keys have no extension, so Rack's mime lookup falls back to its default.
        String mimeType = "text/plain";
        response.header("Content-Type", mimeType);

        List<long[]> ranges = byteRanges(rangeHeader, size);
        boolean head = method.equals("HEAD");
        if (ranges == null || (ranges.size() > 1 && length(ranges) > MAX_RANGE_BYTES)) {
            response.status = 200;
            if (head || size == 0) response.body = new byte[0];
            else response.file = path;
            return;
        }
        if (ranges.isEmpty()) {
            response.status = 416;
            response.body("Byte range unsatisfiable\n".getBytes(StandardCharsets.UTF_8), "text/plain");
            response.header("Content-Range", "bytes */" + size);
            return;
        }
        response.status = 206;
        var body = new ByteArrayOutputStream();
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (ranges.size() == 1) {
                long start = ranges.getFirst()[0];
                long end = Math.min(ranges.getFirst()[1], start + MAX_RANGE_BYTES - 1);
                response.header("Content-Range", "bytes " + start + "-" + end + "/" + size);
                if (!head) copy(channel, start, end, body);
            } else {
                response.header("Content-Type", "multipart/byteranges; boundary=" + MULTIPART_BOUNDARY);
                for (long[] range : ranges) {
                    body.writeBytes(("\r\n--" + MULTIPART_BOUNDARY + "\r\ncontent-type: " + mimeType + "\r\ncontent-range: bytes " + range[0] + "-"
                        + range[1] + "/" + size + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    copy(channel, range[0], range[1], body);
                }
                body.writeBytes(("\r\n--" + MULTIPART_BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
                if (head) body.reset();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        response.body = body.toByteArray();
    }

    private static long length(List<long[]> ranges) {
        long total = 0;
        for (long[] r : ranges) total += r[1] - r[0] + 1;
        return total;
    }

    private static void copy(FileChannel channel, long start, long end, ByteArrayOutputStream out) throws IOException {
        var buffer = java.nio.ByteBuffer.allocate((int) Math.min(64 * 1024, end - start + 1));
        long position = start;
        while (position <= end) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), end - position + 1));
            int n = channel.read(buffer, position);
            if (n <= 0) break;
            out.write(buffer.array(), 0, n);
            position += n;
        }
    }

    /// `Rack::Utils.get_byte_ranges(http_range, size)`: null means "serve everything", an empty
    /// list means 416. Ranges are inclusive `[start, end]` within `size`.
    public static List<long[]> byteRanges(String header, long size) {
        if (header == null || size == 0) return null;
        String spec = rangeSpec(header);
        if (spec == null) return null;
        if (spec.chars().filter(c -> c == ',').count() >= 100) return null;
        var ranges = new ArrayList<long[]>();
        for (String rangeSpec : rubySplit(spec, true)) {
            if (rangeSpec.indexOf('-') < 0) return null;
            List<String> parts = rubySplit(rangeSpec, false);
            String r0 = parts.isEmpty() ? null : parts.get(0);
            String r1 = parts.size() > 1 ? parts.get(1) : null;
            long start;
            long end;
            if (r0 == null || r0.isEmpty()) {
                if (r1 == null) return null;
                start = Math.max(0, size - toI(r1));
                end = size - 1;
            } else {
                start = toI(r0);
                if (r1 == null) {
                    end = size - 1;
                } else {
                    long last = toI(r1);
                    if (last < start) return null;
                    end = Math.min(last, size - 1);
                }
            }
            if (start <= end) ranges.add(new long[] {start, end});
        }
        long total = 0;
        for (long[] r : ranges) {
            total += r[1] - r[0] + 1;
            if (total > size) return List.of();
        }
        return ranges;
    }

    /// `http_range =~ /bytes=([^;]+)/`.
    private static String rangeSpec(String header) {
        int from = 0;
        while (true) {
            int i = header.indexOf("bytes=", from);
            if (i < 0) return null;
            String rest = header.substring(i + 6);
            int semicolon = rest.indexOf(';');
            String spec = semicolon < 0 ? rest : rest.substring(0, semicolon);
            if (!spec.isEmpty()) return spec;
            from = i + 1;
        }
    }

    /// `String#split(/,[ \t]*/)` (`comma`) or `split("-")`, dropping trailing empty fields.
    private static List<String> rubySplit(String s, boolean comma) {
        var fields = new ArrayList<String>();
        int start = 0;
        while (true) {
            int i = s.indexOf(comma ? ',' : '-', start);
            if (i < 0) break;
            fields.add(s.substring(start, i));
            start = i + 1;
            if (comma) while (start < s.length() && (s.charAt(start) == ' ' || s.charAt(start) == '\t')) start++;
        }
        fields.add(s.substring(start));
        while (!fields.isEmpty() && fields.getLast().isEmpty()) fields.removeLast();
        return fields;
    }

    /// Ruby's `String#to_i`, saturating.
    static long toI(String s) {
        int pos = 0;
        while (pos < s.length() && " \t\n\u000b\f\r".indexOf(s.charAt(pos)) >= 0) pos++;
        boolean negative = false;
        if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) negative = s.charAt(pos++) == '-';
        if (s.startsWith("0d", pos) || s.startsWith("0D", pos)) pos += 2;
        long n = 0;
        boolean previousDigit = false;
        for (; pos < s.length(); pos++) {
            char c = s.charAt(pos);
            if (c >= '0' && c <= '9') {
                n = n > (Long.MAX_VALUE - 9) / 10 ? Long.MAX_VALUE : n * 10 + (c - '0');
                previousDigit = true;
            } else if (c == '_' && previousDigit) {
                previousDigit = false;
            } else {
                break;
            }
        }
        return negative ? -n : n;
    }

    static String httpDate(Instant time) {
        return HTTP_DATE.format(time);
    }
}
