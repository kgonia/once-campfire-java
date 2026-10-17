package campfire.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/// Width, height and EXIF orientation of JPEG, PNG, GIF and WebP files as libvips reports them,
/// read from the headers. It's the image analyzer's fallback when `vipsheader` isn't installed
/// (e.g. on a development machine); in the image, `vipsheader` answers.
final class ImageHeaders {
    private ImageHeaders() {}

    /// `orientation` is the EXIF IFD0 Orientation (1-8), or 0 when absent.
    record Header(int width, int height, int orientation) {}

    private static final int MAX_HEADER = 1 << 20;

    /// The header, or null when the format isn't one of these or the header can't be read.
    static Header read(Path file) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(MAX_HEADER);
        } catch (IOException e) {
            return null;
        }
        try {
            if (startsWith(head, 0, 0xff, 0xd8, 0xff)) return jpeg(head);
            if (startsWith(head, 0, 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a)) return png(head);
            if (startsWith(head, 0, 'G', 'I', 'F', '8')) return gif(head);
            if (startsWith(head, 0, 'R', 'I', 'F', 'F') && startsWith(head, 8, 'W', 'E', 'B', 'P')) return webp(head);
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        return null;
    }

    private static Header jpeg(byte[] b) {
        int pos = 2;
        int width = 0;
        int height = 0;
        byte[] exif = null;
        while (pos + 4 <= b.length) {
            if ((b[pos] & 0xff) != 0xff) return null;
            int marker = b[pos + 1] & 0xff;
            if (marker == 0xff) {
                pos++;
                continue;
            }
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                pos += 2;
                continue;
            }
            if (marker == 0xd9 || marker == 0xda) break;
            int length = u16be(b, pos + 2);
            if (length < 2) return null;
            int data = pos + 4;
            boolean sof = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (sof && width == 0) {
                height = u16be(b, data + 1);
                width = u16be(b, data + 3);
            } else if (marker == 0xe1 && length > 8 && startsWith(b, data, 'E', 'x', 'i', 'f', 0, 0)) {
                // libjpeg keeps every APP1; libvips attaches each Exif one in turn, so the last wins.
                exif = java.util.Arrays.copyOfRange(b, data + 6, Math.min(b.length, pos + 2 + length));
            }
            pos += 2 + length;
        }
        if (width == 0 || height == 0) return null;
        return new Header(width, height, exif == null ? 0 : orientation(exif));
    }

    private static Header png(byte[] b) {
        if (!startsWith(b, 12, 'I', 'H', 'D', 'R')) return null;
        int width = s32be(b, 16);
        int height = s32be(b, 20);
        if (width <= 0 || height <= 0) return null;
        int orientation = 0;
        int pos = 8;
        while (pos + 8 <= b.length) {
            int length = s32be(b, pos);
            if (length < 0) break;
            if (startsWith(b, pos + 4, 'I', 'D', 'A', 'T') || startsWith(b, pos + 4, 'I', 'E', 'N', 'D')) break;
            if (startsWith(b, pos + 4, 'e', 'X', 'I', 'f') && pos + 8 + length <= b.length) {
                orientation = orientation(java.util.Arrays.copyOfRange(b, pos + 8, pos + 8 + length));
            }
            pos += 12 + length;
        }
        return new Header(width, height, orientation);
    }

    private static Header gif(byte[] b) {
        if (!startsWith(b, 3, '8', '7', 'a') && !startsWith(b, 3, '8', '9', 'a')) return null;
        int width = u16le(b, 6);
        int height = u16le(b, 8);
        // libnsgif grows the canvas to fit frames that overflow the logical screen; leave those to libvips.
        int pos = 13;
        int flags = b[10] & 0xff;
        if ((flags & 0x80) != 0) pos += 3 * (1 << ((flags & 7) + 1));
        while (pos < b.length) {
            int block = b[pos] & 0xff;
            if (block == 0x3b) break;
            if (block == 0x21) {
                pos = skipSubBlocks(b, pos + 2);
            } else if (block == 0x2c) {
                int left = u16le(b, pos + 1);
                int top = u16le(b, pos + 3);
                int w = u16le(b, pos + 5);
                int h = u16le(b, pos + 7);
                if (left + w > width || top + h > height) return null;
                int localFlags = b[pos + 9] & 0xff;
                pos += 10;
                if ((localFlags & 0x80) != 0) pos += 3 * (1 << ((localFlags & 7) + 1));
                pos = skipSubBlocks(b, pos + 1);
            } else {
                return null;
            }
        }
        if (width == 0 || height == 0) return null;
        return new Header(width, height, 0);
    }

    private static int skipSubBlocks(byte[] b, int pos) {
        while (true) {
            int size = b[pos] & 0xff;
            pos += 1 + size;
            if (size == 0) return pos;
        }
    }

    private static Header webp(byte[] b) {
        int pos = 12;
        int width = 0;
        int height = 0;
        int orientation = 0;
        while (pos + 8 <= b.length) {
            int size = s32le(b, pos + 4);
            if (size < 0) return null;
            int data = pos + 8;
            if (startsWith(b, pos, 'V', 'P', '8', 'X')) {
                width = u24le(b, data + 4) + 1;
                height = u24le(b, data + 7) + 1;
            } else if (startsWith(b, pos, 'V', 'P', '8', ' ') && width == 0) {
                if (!startsWith(b, data + 3, 0x9d, 0x01, 0x2a)) return null;
                width = u16le(b, data + 6) & 0x3fff;
                height = u16le(b, data + 8) & 0x3fff;
            } else if (startsWith(b, pos, 'V', 'P', '8', 'L') && width == 0) {
                if ((b[data] & 0xff) != 0x2f) return null;
                long bits = (b[data + 1] & 0xffL) | (b[data + 2] & 0xffL) << 8 | (b[data + 3] & 0xffL) << 16 | (b[data + 4] & 0xffL) << 24;
                width = (int) (bits & 0x3fff) + 1;
                height = (int) ((bits >> 14) & 0x3fff) + 1;
            } else if (startsWith(b, pos, 'E', 'X', 'I', 'F') && data + size <= b.length) {
                byte[] exif = java.util.Arrays.copyOfRange(b, data, data + size);
                if (startsWith(exif, 0, 'E', 'x', 'i', 'f', 0, 0)) exif = java.util.Arrays.copyOfRange(exif, 6, exif.length);
                orientation = orientation(exif);
            }
            pos = data + size + (size & 1);
        }
        if (width <= 0 || height <= 0) return null;
        return new Header(width, height, orientation);
    }

    /// IFD0's Orientation (a SHORT, as libexif requires) from TIFF-structured EXIF data, or 0.
    static int orientation(byte[] tiff) {
        try {
            boolean little;
            if (startsWith(tiff, 0, 'I', 'I', 42, 0)) little = true;
            else if (startsWith(tiff, 0, 'M', 'M', 0, 42)) little = false;
            else return 0;
            int ifd = (int) u32(tiff, 4, little);
            int count = u16(tiff, ifd, little);
            for (int i = 0; i < count; i++) {
                int entry = ifd + 2 + 12 * i;
                if (u16(tiff, entry, little) == 0x0112) {
                    if (u16(tiff, entry + 2, little) != 3 || u32(tiff, entry + 4, little) < 1) return 0;
                    return u16(tiff, entry + 8, little);
                }
            }
        } catch (IndexOutOfBoundsException e) {
            return 0;
        }
        return 0;
    }

    private static boolean startsWith(byte[] b, int at, int... expected) {
        if (at < 0 || at + expected.length > b.length) return false;
        for (int i = 0; i < expected.length; i++) if ((b[at + i] & 0xff) != expected[i]) return false;
        return true;
    }

    private static int u16be(byte[] b, int at) {
        return (b[at] & 0xff) << 8 | (b[at + 1] & 0xff);
    }

    private static int s32be(byte[] b, int at) {
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }

    private static int u16le(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8;
    }

    private static int u24le(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16;
    }

    private static int s32le(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private static int u16(byte[] b, int at, boolean little) {
        return little ? u16le(b, at) : u16be(b, at);
    }

    private static long u32(byte[] b, int at, boolean little) {
        return (little ? s32le(b, at) : s32be(b, at)) & 0xffffffffL;
    }
}
