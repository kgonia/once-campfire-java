package campfire.view;

import gg.jte.Content;
import gg.jte.html.HtmlTemplateOutput;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Collects a rendered template as UTF-8 bytes. Templates render to it directly, not through jte's
/// `OwaspHtmlTemplateOutput`: escaping `&`, `<`, `>`, `"` and `'` everywhere is right in text and in
/// quoted attributes, and the templates write no output into scripts or event handlers (the one
/// `<script>` is a `text/template` of markup), so the context jte passes isn't needed.
public final class HtmlOut implements HtmlTemplateOutput {
    private byte[] buffer;
    private int length;
    /// Cached fragments written with {@link Safe#handed()}, in order.
    private final List<PageDigest.Handed> handed = new ArrayList<>();

    public HtmlOut(int capacity) {
        buffer = new byte[capacity];
    }

    private void ensure(int extra) {
        if (length + extra > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + extra));
    }

    @Override
    public void setContext(String tagName, String attributeName) {}

    @Override
    public void writeBinaryContent(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buffer, length, bytes.length);
        length += bytes.length;
    }

    @Override
    public void writeContent(String value) {
        if (value == null) return;
        writeContent(value, 0, value.length());
    }

    @Override
    public void writeContent(String value, int beginIndex, int endIndex) {
        ensure((endIndex - beginIndex) * 3);
        for (int i = beginIndex; i < endIndex; i++) {
            char c = value.charAt(i);
            if (c < 0x80) {
                buffer[length++] = (byte) c;
            } else {
                // Rare: hand the rest to the encoder.
                byte[] bytes = value.substring(i, endIndex).getBytes(StandardCharsets.UTF_8);
                ensure(bytes.length);
                System.arraycopy(bytes, 0, buffer, length, bytes.length);
                length += bytes.length;
                return;
            }
        }
    }

    @Override
    public void writeUserContent(String value) {
        if (value == null) return;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            String replacement = switch (value.charAt(i)) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> null;
            };
            if (replacement != null) {
                writeContent(value, start, i);
                writeContent(replacement);
                start = i + 1;
            }
        }
        writeContent(value, start, value.length());
    }

    @Override
    public void writeUserContent(Enum<?> value) {
        if (value != null) writeUserContent(value.toString());
    }

    @Override
    public void writeUserContent(char value) {
        writeUserContent(String.valueOf(value));
    }

    @Override
    public void writeUserContent(Character value) {
        if (value != null) writeUserContent(String.valueOf(value));
    }

    @Override
    public void writeUserContent(Content content) {
        if (content != null) content.writeTo(this);
    }

    /// Writes `fragment`, noting where it went when it's big enough to count as a fragment part.
    void writeHanded(Safe fragment) {
        byte[] bytes = fragment.utf8();
        if (bytes.length >= PageDigest.MIN_FRAGMENT) handed.add(new PageDigest.Handed(length, fragment));
        writeBinaryContent(bytes);
    }

    /// The rendered bytes; when fragments were handed, their {@link PageDigest} is remembered with
    /// the array returned.
    public byte[] body() {
        byte[] body = toByteArray();
        if (!handed.isEmpty()) PageDigest.remember(body, PageDigest.digest(body, handed));
        return body;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, length);
    }

    @Override
    public String toString() {
        return new String(buffer, 0, length, StandardCharsets.UTF_8);
    }
}
