package campfire.integrations.opengraph;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/// `Opengraph::Document` (reference/app/models/opengraph/document.rb), over jsoup.
///
/// Rails parses with `Nokogiri::HTML` (libxml2's pre-HTML5 parser); jsoup is an HTML5 parser,
/// which agrees on ordinary pages but not on every malformed one. Known differences (from the
/// Rust port's probes of the reference): HTML5 decodes some references libxml2 leaves alone
/// (`&eacute` without `;`, `&AMP;`) and maps `&#128;`–`&#159;` to Windows-1252; a `<meta>`
/// inside `<title>`, `<textarea>`, `<noscript>` or `<template>` isn't an element in HTML5
/// (libxml2 finds it); a NUL doesn't end the document. None of these affect the metadata
/// of a well-formed page.
final class Document {
    private Document() {}

    /// `Opengraph::Metadata::ATTRIBUTES`, in the order `Hash#slice` returns them.
    static final List<String> ATTRIBUTES = List.of("title", "url", "image", "description");

    /// `opengraph_attributes`: from each `meta` whose `property` or `name` starts with "og:", the
    /// key is that attribute (`property` when present) with every "og:" removed, and the value
    /// its non-blank `content`. Later tags win. Without a meta charset, non-ASCII characters are
    /// dropped (`content.encode("UTF-8", "binary", invalid: :replace, undef: :replace, replace: "")`).
    static Map<String, String> opengraphAttributes(byte[] body) {
        var metas = Jsoup.parse(decode(body == null ? new byte[0] : body)).getElementsByTag("meta");
        boolean hasEncoding = metaEncoding(metas) != null;

        String[] found = new String[ATTRIBUTES.size()];
        for (Element meta : metas) {
            if (!meta.attr("property").startsWith("og:") && !meta.attr("name").startsWith("og:")) continue;
            String key = meta.hasAttr("property") ? "property" : "name";
            int index = ATTRIBUTES.indexOf(meta.attr(key).replace("og:", ""));
            if (index < 0 || !meta.hasAttr("content") || isBlank(meta.attr("content"))) continue;
            String content = meta.attr("content");
            found[index] = hasEncoding ? content : asciiOnly(content);
        }
        var attributes = new LinkedHashMap<String, String>();
        for (int i = 0; i < found.length; i++) if (found[i] != null) attributes.put(ATTRIBUTES.get(i), found[i]);
        return attributes;
    }

    /// The body as libxml2 reads the UTF-8-tagged string `Opengraph::Fetch` returns: valid
    /// UTF-8 decodes, any other byte is taken as Latin-1 (whatever the page declares).
    static String decode(byte[] bytes) {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        var in = ByteBuffer.wrap(bytes);
        var out = CharBuffer.allocate(bytes.length + 1);
        while (true) {
            CoderResult result = utf8.decode(in, out, true);
            if (result.isUnderflow()) break;
            if (result.isError()) {
                // one invalid byte at a time: the first becomes Latin-1, decoding resumes after it
                out.put((char) (in.get() & 0xff));
                utf8.reset();
            }
        }
        utf8.flush(out);
        return out.flip().toString();
    }

    /// `Nokogiri::HTML4::Document#meta_encoding`: the first `meta[@charset]`, else the charset in
    /// the first `http-equiv="Content-Type"` meta with a `content`.
    static String metaEncoding(List<Element> metas) {
        for (var meta : metas) if (meta.hasAttr("charset")) return meta.attr("charset");
        for (var meta : metas) {
            if (meta.hasAttr("content") && meta.attr("http-equiv").equalsIgnoreCase("content-type")) {
                var m = CHARSET.matcher(meta.attr("content"));
                return m.find() ? m.group(1) : null;
            }
        }
        return null;
    }

    /// `content[/charset\s*=\s*([\w-]+)/i, 1]`
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*([A-Za-z0-9_-]+)");

    private static String asciiOnly(String s) {
        var out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x80) out.append(s.charAt(i));
        return out.toString();
    }

    /// `String#blank?`: empty or only (Unicode) whitespace.
    static boolean isBlank(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (!isUnicodeWhitespace(c)) return false;
            i += Character.charCount(c);
        }
        return true;
    }

    /// The Unicode `White_Space` property (Ruby's `[[:space:]]`).
    private static boolean isUnicodeWhitespace(int c) {
        return (c >= 0x09 && c <= 0x0d) || c == 0x20 || c == 0x85 || c == 0xa0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200a) || c == 0x2028
            || c == 0x2029 || c == 0x202f || c == 0x205f || c == 0x3000;
    }
}
