package campfire.integrations.qr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Draws QR codes and reads them back with ZXing.
class QrCodeTest {
    private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"0 0 (\\d+) \\1\"");
    private static final Pattern MODULE = Pattern.compile("M(\\d+),(\\d+)h1v1h-1z");

    /// What a scanner reads from the SVG, with its error correction level.
    private static String[] scan(String svg) throws Exception {
        Matcher size = VIEW_BOX.matcher(svg);
        assertTrue(size.find(), svg);
        int modules = Integer.parseInt(size.group(1));
        // Four pixels a module, inside the four-module quiet zone a scanner needs.
        int scale = 4, border = 4, width = (modules + 2 * border) * scale;
        int[] pixels = new int[width * width];
        Arrays.fill(pixels, 0xffffff);
        for (Matcher m = MODULE.matcher(svg); m.find(); ) {
            int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2));
            for (int dy = 0; dy < scale; dy++) {
                int row = (y + border) * scale + dy;
                Arrays.fill(pixels, row * width + (x + border) * scale, row * width + (x + border + 1) * scale, 0);
            }
        }
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, width, pixels)));
        var result = new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.PURE_BARCODE, true));
        return new String[] {result.getText(), (String) result.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL)};
    }

    @Test
    void encodesTheUrl() throws Exception {
        for (String url : List.of("http://campfire.test", "https://campfire.example.com/session/transfers/1a2b3c4d5e6f?x=1",
            "https://chat.example.com/join/" + "AbC1".repeat(60))) {
            String svg = QrCode.svg(url.getBytes(StandardCharsets.UTF_8));
            assertTrue(svg.startsWith("<?xml version=\"1.0\" standalone=\"yes\"?><svg xmlns=\"http://www.w3.org/2000/svg\""), svg);
            assertTrue(svg.endsWith("</svg>"));
            String[] scanned = scan(svg);
            assertEquals(url, scanned[0]);
            assertEquals("H", scanned[1]);
        }
    }

    @Test
    void dataTooLongForVersion40IsNull() {
        assertNull(QrCode.svg("a".repeat(3000).getBytes(StandardCharsets.UTF_8)));
    }
}
