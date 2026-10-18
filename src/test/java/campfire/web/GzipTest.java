package campfire.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.view.HtmlOut;
import campfire.view.Safe;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

class GzipTest {
    /// Large fragments are spliced in already compressed; the result must still decompress to the
    /// body exactly, with text before, between and after them and a small fragment left inline.
    @Test
    void splicedFragmentsDecompressToTheBody() throws Exception {
        Safe importmap = Safe.bytes("<script type=\"importmap\">{\"a\": \"/assets/a.js\"}</script>\n".repeat(200).getBytes(UTF_8));
        Safe styles = Safe.bytes("<link rel=\"stylesheet\" href=\"/assets/b.css\" />\n".repeat(150).getBytes(UTF_8));
        Safe message = Safe.bytes(("<div class=\"message\">" + "hello ".repeat(300) + "</div>").getBytes(UTF_8));
        var out = new HtmlOut(1024);
        out.writeContent("<!DOCTYPE html><html><head>");
        out.writeUserContent(styles.handed());
        out.writeContent("<meta name=\"x\">".repeat(40));
        out.writeUserContent(importmap.handed());
        out.writeContent("</head><body>");
        out.writeUserContent(message.handed());
        out.writeContent("<p>after</p>".repeat(100) + "</body></html>");
        byte[] body = out.body();

        byte[] gzip = Gzip.compress(body);
        assertArrayEquals(body, new GZIPInputStream(new ByteArrayInputStream(gzip)).readAllBytes());
        // Twice: the second response reuses the compressed blocks.
        assertArrayEquals(gzip, Gzip.compress(body));
        assertTrue(gzip.length < body.length / 10, gzip.length + " of " + body.length);
    }
}
