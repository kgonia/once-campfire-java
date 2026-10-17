package campfire.view;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The ETag digest of a page with cached message fragments in it: rather than the whole body, it
/// hashes the page split into parts (the text between fragments, and each fragment by its own
/// SHA-256), so the same page always gets the same tag without hashing its ~400 KB again.
///
/// Parts: a fragment is one part, carrying as glue the text since the previous fragment when that
/// is at most {@link #MAX_GLUE} bytes; longer gaps, and text before the first fragment or after
/// the last, are text parts. A text part hashes as `T`, its length (u64 little-endian) and its
/// SHA-256; a fragment as `F`, the glue's length, the glue, the fragment's length and its SHA-256.
/// The digest is the first 16 bytes of the SHA-256 of that, in hex.
@NullMarked
public final class PageDigest {
    private PageDigest() {}

    /// Writes shorter than this are plain text, fragment or not.
    public static final int MIN_FRAGMENT = 1024;
    static final int MAX_GLUE = 256;

    /// A handed fragment at its byte offset in the body.
    record Handed(int offset, Safe fragment) {}

    /// Digests of bodies rendered with fragments, keyed by the body array itself (weak keys compare
    /// by identity), for the response filter that tags the body later in the request.
    private static final Cache<byte[], String> BODIES = Caffeine.newBuilder().weakKeys().maximumSize(10_000).build();

    /// The part digest for `body` if it was rendered with fragments, else null.
    public static @Nullable String of(byte[] body) {
        return BODIES.getIfPresent(body);
    }

    /// What identifies `body` as a validator: its part digest, else its whole SHA-256 in hex.
    public static String validator(byte[] body) {
        String digest = of(body);
        return digest != null ? digest : HexFormat.of().formatHex(sha256().digest(body));
    }

    static void remember(byte[] body, String digest) {
        BODIES.put(body, digest);
    }

    /// The digest of `body` with `handed` (in order) at their offsets.
    static String digest(byte[] body, List<Handed> handed) {
        MessageDigest page = sha256();
        int position = 0;
        boolean followsFragment = false;
        for (Handed h : handed) {
            int gap = h.offset() - position;
            byte[] fragment = h.fragment().utf8();
            if (followsFragment && gap <= MAX_GLUE) {
                page.update((byte) 'F');
                page.update(u64(gap));
                page.update(body, position, gap);
            } else {
                if (gap > 0) text(page, body, position, gap);
                page.update((byte) 'F');
                page.update(u64(0));
            }
            page.update(u64(fragment.length));
            page.update(h.fragment().sha256());
            position = h.offset() + fragment.length;
            followsFragment = true;
        }
        if (position < body.length) text(page, body, position, body.length - position);
        return HexFormat.of().formatHex(page.digest(), 0, 16);
    }

    private static void text(MessageDigest page, byte[] body, int offset, int length) {
        MessageDigest text = sha256();
        text.update(body, offset, length);
        page.update((byte) 'T');
        page.update(u64(length));
        page.update(text.digest());
    }

    private static byte[] u64(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
