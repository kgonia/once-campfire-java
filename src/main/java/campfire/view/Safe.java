package campfire.view;

import gg.jte.Content;
import gg.jte.TemplateOutput;
import java.nio.charset.StandardCharsets;

/// Markup that is already safe (`html_safe`): written as is.
public final class Safe implements Content {
    public static final Safe EMPTY = new Safe("");

    private final String html;
    private byte[] bytes;
    private byte[] sha256;
    private boolean everyPage;

    private Safe(String html) {
        this.html = html;
    }

    private Safe(byte[] bytes) {
        this.html = null;
        this.bytes = bytes;
    }

    public static Safe of(String html) {
        return html == null || html.isEmpty() ? EMPTY : new Safe(html);
    }

    public static Safe bytes(byte[] bytes) {
        return new Safe(bytes);
    }

    /// Markup that many pages repeat exactly, such as the layout's head tags: gzip compresses it
    /// once and splices the result into each response (see `Gzip`). Not for a record's fragments:
    /// alone they compress far worse than next to their neighbours.
    public static Safe everyPage(byte[] bytes) {
        var safe = new Safe(bytes);
        safe.everyPage = true;
        return safe;
    }

    public boolean isEveryPage() {
        return everyPage;
    }

    /// The markup as UTF-8.
    public byte[] utf8() {
        if (bytes == null) bytes = html.getBytes(StandardCharsets.UTF_8);
        return bytes;
    }

    /// The SHA-256 of {@link #utf8()}, computed once.
    public byte[] sha256() {
        if (sha256 == null) sha256 = PageDigest.sha256().digest(utf8());
        return sha256;
    }

    /// This markup written as a cached fragment the page notes by position, so the page's ETag
    /// digests it by {@link #sha256()} (see {@link PageDigest}). Same bytes as writing it plainly.
    public Content handed() {
        return output -> {
            if (output instanceof HtmlOut out) out.writeHanded(this);
            else writeTo(output);
        };
    }

    @Override
    public void writeTo(TemplateOutput output) {
        if (bytes != null) output.writeBinaryContent(bytes);
        else output.writeContent(html);
    }

    @Override
    public boolean isEmptyContent() {
        return bytes != null ? bytes.length == 0 : html.isEmpty();
    }

    @Override
    public String toString() {
        return html != null ? html : new String(bytes, StandardCharsets.UTF_8);
    }
}
