package campfire.web;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Gzip members as zlib writes them at its default level (6): the header carries `mtime` (epoch
/// seconds, 0 for none), the Unix OS code and, when given, a comment (the proxy's BREACH padding).
/// Deflaters are pooled; virtual threads make thread-locals a poor fit.
@NullMarked
public final class Gzip {
    private Gzip() {}

    private static final ConcurrentLinkedQueue<Deflater> POOL = new ConcurrentLinkedQueue<>();
    private static final int OS_UNIX = 3;
    private static final int FLAG_COMMENT = 0x10;

    public static byte[] compress(byte[] body) {
        return compress(body, 0, null);
    }

    public static byte[] compress(byte[] body, long mtime, @Nullable String comment) {
        var out = new ByteArrayOutputStream(Math.max(64, body.length / 4));
        try (var gzip = stream(out, mtime, comment)) {
            gzip.write(body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /// Compresses what's written to it into `out`; closing it writes the trailer and closes `out`.
    public static OutputStream stream(OutputStream out, long mtime, @Nullable String comment) throws IOException {
        int flags = comment == null ? 0 : FLAG_COMMENT;
        out.write(new byte[] {0x1f, (byte) 0x8b, 8, (byte) flags, (byte) mtime, (byte) (mtime >> 8), (byte) (mtime >> 16), (byte) (mtime >> 24),
            0, OS_UNIX});
        if (comment != null) {
            out.write(comment.getBytes(StandardCharsets.ISO_8859_1));
            out.write(0);
        }
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
