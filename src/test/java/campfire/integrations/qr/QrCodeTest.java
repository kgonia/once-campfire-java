package campfire.integrations.qr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.integrations.TestSupport;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Golden vectors from rqrcode 3.2.0 (the Rust port's `reference-tools/campfire/rqrcode.rb`).
class QrCodeTest {
    @Test
    @SuppressWarnings("unchecked")
    void matchesRqrcode() {
        var vectors = (List<Map<String, Object>>) TestSupport.json("rqrcode.json");
        assertTrue(vectors.size() >= 30);
        int svgs = 0;
        for (var vector : vectors) {
            byte[] input = Base64.getDecoder().decode((String) vector.get("input_base64"));
            String name = (String) vector.get("input_base64");
            assertEquals(((Number) vector.get("version")).intValue(), QrCode.minimumVersion(input), "version for " + name);
            var modules = QrCode.modules(input);
            var rows = new StringBuilder();
            for (boolean[] row : modules) {
                if (!rows.isEmpty()) rows.append('\n');
                for (boolean m : row) rows.append(m ? '1' : '0');
            }
            assertEquals(vector.get("modules"), rows.toString(), "modules for " + name);
            if (vector.get("svg") instanceof String svg) {
                assertEquals(svg, QrCode.svg(input), "svg for " + name);
                svgs++;
            }
        }
        assertTrue(svgs > 0);
    }

    @Test
    void dataTooLongForVersion40IsNull() {
        assertNull(QrCode.svg("a".repeat(3000)));
        assertNotNull(QrCode.svg("http://campfire.test"));
    }
}
