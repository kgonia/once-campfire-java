package campfire.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Response compression. `GZIPOutputStream` brings a new `Deflater` (and its native tables) per
/// stream; these are pooled instead, since virtual threads make thread-locals a poor fit.
@NullMarked
public final class Gzip {
    private Gzip() {}

    private static final ConcurrentLinkedQueue<Deflater> POOL = new ConcurrentLinkedQueue<>();
    /// No mtime, Unix: the 10-byte header zlib writes.
    private static final byte[] HEADER = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, 3};

    /// Whether `Accept-Encoding` takes gzip: named (or `*`) without `q=0`.
    public static boolean accepted(@Nullable String acceptEncoding) {
        if (acceptEncoding == null) return false;
        Boolean wildcard = null;
        for (String part : acceptEncoding.split(",")) {
            String[] pieces = part.split(";");
            String coding = pieces[0].strip().toLowerCase(Locale.ROOT);
            boolean allowed = true;
            for (int i = 1; i < pieces.length; i++) {
                String parameter = pieces[i].strip();
                if (parameter.startsWith("q=")) allowed = !zero(parameter.substring(2));
            }
            if (coding.equals("gzip") || coding.equals("x-gzip")) return allowed;
            if (coding.equals("*")) wildcard = allowed;
        }
        return wildcard != null && wildcard;
    }

    private static boolean zero(String quality) {
        try {
            return Double.parseDouble(quality.strip()) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /// Text compresses; images, audio, video and archives already are.
    public static boolean compressible(@Nullable String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/") || type.contains("json") || type.contains("javascript") || type.contains("xml") || type.startsWith("image/svg");
    }

    public static byte[] compress(byte[] body) {
        if (body.length > SMALL_BODY) return deflate(body);
        byte[] gzip = SMALL.getIfPresent(ByteBuffer.wrap(body));
        if (gzip == null) {
            gzip = deflate(body);
            SMALL.put(ByteBuffer.wrap(body.clone()), gzip);
        }
        return gzip;
    }

    /// Small bodies repeat (health checks, short Turbo Streams), and for them resetting the
    /// deflater's tables costs more than compressing. Callers never modify what they get back.
    private static final int SMALL_BODY = 1024;
    private static final Cache<ByteBuffer, byte[]> SMALL = Caffeine.newBuilder().maximumSize(512).build();

    private static byte[] deflate(byte[] body) {
        var out = new ByteArrayOutputStream(Math.max(64, body.length / 4));
        try (var gzip = stream(out)) {
            gzip.write(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /// Compresses what's written to it into `out`; closing it writes the trailer and closes `out`.
    public static OutputStream stream(OutputStream out) throws IOException {
        out.write(HEADER);
        return new Member(out);
    }

    /// The deflate stream and CRC-32/size trailer of one member.
    private static final class Member extends FilterOutputStream {
        private final Deflater deflater;
        private final CRC32 crc = new CRC32();
        private final byte[] buffer = new byte[16384];
        private long size;
        private boolean closed;

        Member(OutputStream out) {
            super(out);
            Deflater pooled = POOL.poll();
            this.deflater = pooled != null ? pooled : new Deflater(6, true);
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len == 0) return;
            crc.update(b, off, len);
            size += len;
            deflater.setInput(b, off, len);
            while (!deflater.needsInput()) drain();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                deflater.finish();
                while (!deflater.finished()) drain();
                long c = crc.getValue();
                out.write(new byte[] {(byte) c, (byte) (c >> 8), (byte) (c >> 16), (byte) (c >> 24),
                    (byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)});
                out.close();
            } finally {
                deflater.reset();
                POOL.offer(deflater);
            }
        }

        private void drain() throws IOException {
            int n = deflater.deflate(buffer);
            if (n > 0) out.write(buffer, 0, n);
        }
    }
}
