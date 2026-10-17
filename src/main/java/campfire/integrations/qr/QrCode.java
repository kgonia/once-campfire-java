package campfire.integrations.qr;

import io.nayuki.qrcodegen.QrSegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// `RQRCode::QRCode.new(url).as_svg(viewbox: true, fill: :white, color: :black)` for
/// `QrCodeController#show` (reference/app/controllers/qr_code_controller.rb), byte-identical to
/// rqrcode 3.2.0 / rqrcode_core 2.1.0.
///
/// qrcodegen does the encoding (Reed-Solomon, module placement, masking), but rqrcode makes
/// three choices differently from the QR spec, and qrcodegen's defaults (which match the gem's
/// matrix for only 17 of the 39 golden vectors) can't express them, so they are made here:
/// a single segment whose mode is numeric, alphanumeric or 8-bit byte in that order of
/// preference (empty input is numeric), error correction level H at the smallest version whose
/// capacity is *strictly* greater than the segment's bits, and the mask with the fewest "lost
/// points" as the gem scores them (its own penalty rules, including a floating-point dark-ratio
/// term, on the matrix with format and version information left light).
public final class QrCode {
    private QrCode() {}

    /// The SVG for `text`'s UTF-8 bytes, or null when it doesn't fit a version 40 code (where
    /// rqrcode raises).
    public static String svg(String text) {
        return svg(text.getBytes(StandardCharsets.UTF_8));
    }

    /// The SVG for a binary string (what `Base64.urlsafe_decode64` returns), or null when it
    /// doesn't fit a version 40 code.
    public static String svg(byte[] data) {
        boolean[][] modules = modules(data);
        return modules == null ? null : toSvg(modules);
    }

    private static final int MODULE_SIZE = 11;

    /// rqrcode's `as_svg` (Rect output) with `viewbox: true, fill: :white, color: :black`.
    private static String toSvg(boolean[][] modules) {
        int dimension = modules.length * MODULE_SIZE;
        var out = new StringBuilder(256 + modules.length * modules.length * 30);
        out.append("<?xml version=\"1.0\" standalone=\"yes\"?>");
        out.append("<svg version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
            + "xmlns:ev=\"http://www.w3.org/2001/xml-events\" viewBox=\"0 0 ").append(dimension).append(' ').append(dimension)
            .append("\" shape-rendering=\"crispEdges\">");
        out.append("<rect width=\"").append(dimension).append("\" height=\"").append(dimension).append("\" x=\"0\" y=\"0\" fill=\"white\"/>");
        for (int row = 0; row < modules.length; row++) {
            for (int col = 0; col < modules.length; col++) {
                if (!modules[row][col]) continue;
                out.append("<rect width=\"11\" height=\"11\" x=\"").append(col * MODULE_SIZE).append("\" y=\"").append(row * MODULE_SIZE)
                    .append("\" fill=\"black\"/>");
            }
        }
        return out.append("</svg>").toString();
    }

    private static final String ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    /// `QRMAXBITS[:h]`: data bits per version at level H.
    private static final int[] MAX_BITS_H = {72, 128, 208, 288, 368, 480, 528, 688, 800, 976, 1120, 1264, 1440, 1576, 1784, 2024, 2264, 2504,
        2728, 3080, 3248, 3536, 3712, 4112, 4304, 4768, 5024, 5288, 5608, 5960, 6344, 6760, 7208, 7688, 7888, 8432, 8768, 9136, 9776, 10208};

    private enum Mode { NUMBER, ALPHA_NUMERIC, BYTE }

    /// `QRSegment.new(data:)` without a mode: numeric, then alphanumeric, then 8-bit byte.
    private static Mode mode(byte[] data) {
        boolean numeric = true, alphanumeric = true;
        for (byte b : data) {
            numeric &= b >= '0' && b <= '9';
            alphanumeric &= b >= 0 && ALPHANUMERIC.indexOf(b) >= 0;
        }
        return numeric ? Mode.NUMBER : alphanumeric ? Mode.ALPHA_NUMERIC : Mode.BYTE;
    }

    /// `QRSegment#size(version)`: bits for the mode indicator, the length and the content.
    private static int size(Mode mode, int length, int version) {
        int macro = version <= 9 ? 0 : version <= 26 ? 1 : 2;
        return 4 + switch (mode) {
            case NUMBER -> new int[] {10, 12, 14}[macro] + (length / 3) * 10 + new int[] {0, 4, 7}[length % 3];
            case ALPHA_NUMERIC -> new int[] {9, 11, 13}[macro] + (length / 2) * 11 + (length % 2) * 6;
            case BYTE -> new int[] {8, 16, 16}[macro] + length * 8;
        };
    }

    /// `minimum_version`: the first version whose capacity is strictly greater than the bits
    /// needed, or 0 where rqrcode raises "Data length exceed maximum capacity of version 40".
    static int minimumVersion(byte[] data) {
        Mode mode = mode(data);
        for (int version = 1; version <= 40; version++) if (size(mode, data.length, version) < MAX_BITS_H[version - 1]) return version;
        return 0;
    }

    /// The module matrix rqrcode makes (`[row][column]`, `true` is dark), or null when `data`
    /// doesn't fit.
    public static boolean[][] modules(byte[] data) {
        int version = minimumVersion(data);
        if (version == 0) return null;
        String text = new String(data, StandardCharsets.ISO_8859_1);
        QrSegment segment = switch (mode(data)) {
            case NUMBER -> QrSegment.makeNumeric(text);
            case ALPHA_NUMERIC -> QrSegment.makeAlphanumeric(text);
            case BYTE -> QrSegment.makeBytes(data);
        };
        // `get_best_mask_pattern`: the first pattern with the fewest lost points.
        boolean[][] best = null;
        double bestPoints = Double.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] modules = matrix(io.nayuki.qrcodegen.QrCode.encodeSegments(List.of(segment), io.nayuki.qrcodegen.QrCode.Ecc.HIGH,
                version, version, mask, false));
            double points = lostPoints(withoutFormatInformation(modules, version));
            if (best == null || bestPoints > points) {
                best = modules;
                bestPoints = points;
            }
        }
        return best;
    }

    private static boolean[][] matrix(io.nayuki.qrcodegen.QrCode code) {
        boolean[][] modules = new boolean[code.size][code.size];
        for (int row = 0; row < code.size; row++) for (int col = 0; col < code.size; col++) modules[row][col] = code.getModule(col, row);
        return modules;
    }

    /// The matrix rqrcode scores masks on (`make_impl(true, pattern)`): format information,
    /// version information and the dark module left light.
    private static boolean[][] withoutFormatInformation(boolean[][] modules, int version) {
        int count = modules.length;
        boolean[][] test = new boolean[count][];
        for (int i = 0; i < count; i++) test[i] = modules[i].clone();
        for (int i = 0; i <= 8; i++) {
            if (i == 6) continue;
            test[i][8] = false;
            test[8][i] = false;
        }
        for (int i = 0; i < 8; i++) {
            test[count - 1 - i][8] = false;
            test[8][count - 1 - i] = false;
        }
        if (version >= 7) {
            for (int i = 0; i < 18; i++) {
                test[i / 3][i % 3 + count - 11] = false;
                test[i % 3 + count - 11][i / 3] = false;
            }
        }
        return test;
    }

    /// `QRUtil.get_lost_points`. The dark-ratio term is a Float in Ruby, so the total is too.
    private static double lostPoints(boolean[][] modules) {
        int count = modules.length;
        int max = count - 1;
        long points = 0;

        // Same-color neighbours.
        for (int row = 0; row < count; row++) {
            for (int col = 0; col < count; col++) {
                boolean dark = modules[row][col];
                int same = 0;
                for (int r = Math.max(0, row - 1); r <= Math.min(max, row + 1); r++) {
                    for (int c = Math.max(0, col - 1); c <= Math.min(max, col + 1); c++) {
                        if ((r != row || c != col) && modules[r][c] == dark) same++;
                    }
                }
                if (same > 5) points += 3 + same - 5;
            }
        }

        // 2x2 blocks.
        for (int row = 0; row < max; row++) {
            for (int col = 0; col < max; col++) {
                boolean value = modules[row][col];
                if (value == modules[row + 1][col] && value == modules[row][col + 1] && value == modules[row + 1][col + 1]) points += 3;
            }
        }

        // 1:1:3:1:1 patterns, in rows and columns.
        for (int start = 0; start + 6 < count; start++) {
            for (int line = 0; line < count; line++) {
                boolean[] row = modules[line];
                if (row[start] && !row[start + 1] && row[start + 2] && row[start + 3] && row[start + 4] && !row[start + 5] && row[start + 6]) {
                    points += 40;
                }
                if (modules[start][line] && !modules[start + 1][line] && modules[start + 2][line] && modules[start + 3][line]
                    && modules[start + 4][line] && !modules[start + 5][line] && modules[start + 6][line]) {
                    points += 40;
                }
            }
        }

        // Dark ratio.
        long dark = 0;
        for (boolean[] row : modules) for (boolean m : row) if (m) dark++;
        double ratio = (double) dark / (count * count);
        return points + Math.abs(100.0 * ratio - 50.0) / 5.0 * 10.0;
    }
}
