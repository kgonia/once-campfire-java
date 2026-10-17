package campfire.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import campfire.storage.Blob;
import campfire.rails.MessageVerifier;
import campfire.rails.Secrets;
import campfire.storage.RubyMarshal.Sym;
import io.vertx.core.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/// Golden vectors from the Rails reference (`vectors/storage.json`) that need neither libvips nor
/// ffmpeg: Marcel, filenames, variation digests and keys, verifier messages and route paths.
class StorageVectorsTest {
    @SuppressWarnings("unchecked")
    static final Map<String, Object> V = (Map<String, Object>) load("/vectors/storage.json");
    static final String SECRET_KEY_BASE = (String) ((Map<?, ?>) load("/vectors/rails_compat.json")).get("secret_key_base");
    static final MessageVerifier VERIFIER = new Secrets(SECRET_KEY_BASE).appVerifier("ActiveStorage");
    static final Path FIXTURES = Path.of("reference/test/fixtures/files");
    static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    static Object load(String path) {
        try (var in = StorageVectorsTest.class.getResourceAsStream(path)) {
            return Json.decodeValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> section(String name) {
        return (List<Map<String, Object>>) V.get(name);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /// The vectors' typed Ruby values: `{"hash": [[k, v]...]}`, `{"sym": s}`, `{"str": s}`.
    static Object typed(Object value) {
        return switch (value) {
            case null -> null;
            case Integer i -> (long) i;
            case Map<?, ?> m when m.containsKey("sym") -> new Sym((String) m.get("sym"));
            case Map<?, ?> m when m.containsKey("str") -> m.get("str");
            case Map<?, ?> m -> {
                var out = new LinkedHashMap<String, Object>();
                for (Object pair : (List<?>) m.get("hash")) out.put((String) ((List<?>) pair).get(0), typed(((List<?>) pair).get(1)));
                yield out;
            }
            case List<?> l -> {
                var out = new ArrayList<>();
                for (Object o : l) out.add(typed(o));
                yield out;
            }
            default -> value;
        };
    }

    @SuppressWarnings("unchecked")
    static Variation variation(Object typedValue) {
        return new Variation((Map<String, Object>) typed(typedValue));
    }

    static Blob blob(Map<String, Object> row) {
        return new Blob(((Number) row.get("id")).longValue(), (String) row.get("key"), (String) row.get("filename"), (String) row.get("content_type"),
            (String) row.get("metadata"), (String) row.get("service_name"), ((Number) row.get("byte_size")).longValue(), (String) row.get("checksum"),
            NOW);
    }

    @Test
    void secretMatchesTheVectors() {
        assertTrue(SECRET_KEY_BASE.startsWith((String) V.get("secret_key_base_prefix")));
    }

    @Test
    void variationDigestsAndKeys() {
        for (var v : section("variations")) {
            Variation variation = variation(v.get("typed"));
            assertEquals(v.get("marshal_hex"), HexFormat.of().formatHex(variation.marshal()), (String) v.get("inspect"));
            assertEquals(v.get("digest"), variation.digest(), (String) v.get("inspect"));
            assertEquals(v.get("key"), variation.key(VERIFIER), (String) v.get("inspect"));

            Variation decoded = Variation.decode(VERIFIER, (String) v.get("key"), NOW);
            assertEquals(variation(v.get("decoded_typed")), decoded, (String) v.get("decoded_inspect"));
            assertEquals(v.get("decoded_marshal_hex"), HexFormat.of().formatHex(decoded.marshal()));
            assertEquals(v.get("decoded_digest"), decoded.digest());
        }
        assertNull(Variation.decode(VERIFIER, "garbage--0000", NOW));
    }

    @Test
    void namedVariantsAndDefaults() {
        var moon = blob(map(section("messages").get(0).get("blob")));
        var thumb = map(((List<?>) section("messages").get(0).get("variants")).getFirst());
        var storage = new Storage(Path.of("unused"), VERIFIER, null, 1 << 20);
        assertEquals(variation(thumb.get("transformations_typed")), storage.variationFor(moon, Variation.THUMB));
        assertEquals(thumb.get("variation_digest"), storage.variationFor(moon, Variation.THUMB).digest());
        for (var avatar : section("avatars")) {
            var v = map(((List<?>) avatar.get("variants")).getFirst());
            assertEquals(v.get("variation_digest"), storage.variationFor(blob(map(avatar.get("blob"))), Variation.AVATAR_SQUARE).digest());
            if (avatar.get("path") != null) assertEquals(avatar.get("path"), storage.variantPath(blob(map(avatar.get("blob"))), Variation.AVATAR_SQUARE));
        }
        var logo = section("logos").getFirst();
        var variants = (List<?>) logo.get("variants");
        assertEquals(map(variants.get(0)).get("variation_digest"), storage.variationFor(blob(map(logo.get("blob"))), Variation.LOGO_LARGE).digest());
        assertEquals(map(variants.get(1)).get("variation_digest"), storage.variationFor(blob(map(logo.get("blob"))), Variation.LOGO_SMALL).digest());
        // Video posters: the preview's own variant (symbols), and the one decoded from the URL key (strings).
        var video = section("messages").get(3);
        var videoVariants = (List<?>) video.get("variants");
        Blob previewImage = blob(map(map(video.get("preview_image")).get("blob")));
        assertEquals(map(videoVariants.get(0)).get("variation_digest"), storage.variationFor(previewImage, Variation.VIDEO_PREVIEW).digest());
        assertEquals(map(videoVariants.get(1)).get("variation_digest"), storage.variationFor(previewImage, Variation.VIDEO_POSTER).digest());
        Variation fromKey = Variation.decode(VERIFIER, Variation.VIDEO_POSTER.key(VERIFIER), NOW);
        assertEquals(map(videoVariants.get(2)).get("variation_digest"), storage.variationFor(previewImage, fromKey).digest());
    }

    /// The signed key in a disk URL holds JSON that Rails writes with `<`, `>` and `&` escaped, so
    /// for a filename with them only what the key says can match.
    private static void assertSameDiskUrl(Object expected, String actual) {
        String[] want = ((String) expected).split("/"), got = actual.split("/");
        assertEquals(want.length, got.length, actual);
        for (int i = 0; i < want.length; i++) {
            if (i != 4) {
                assertEquals(want[i], got[i], actual);
                continue;
            }
            Object key = VERIFIER.verified(got[i], "blob_key", NOW);
            assertNotNull(key, actual);
            assertEquals(VERIFIER.verified(want[i], "blob_key", NOW), key);
        }
    }

    @Test
    void verifierMessagesAndDiskUrls() {
        var v = map(V.get("verifier"));
        Instant expiresAt = Instant.parse("2030-01-02T03:04:05.678Z");
        assertEquals(v.get("expiring"), VERIFIER.generateRaw("\"x\"", "p", expiresAt));
        assertEquals("x", VERIFIER.verified((String) v.get("expiring"), "p", NOW));
        assertNull(VERIFIER.verified((String) v.get("expiring"), "p", expiresAt));
        assertNull(VERIFIER.verified((String) v.get("expiring"), "q", NOW));

        var disk = new Disk(Path.of("unused"), "local");
        var weird = new Filename("weird & <name> ünï.png");
        String key = "abcdefghijklmnopqrstuvwxyz12";
        assertSameDiskUrl(v.get("disk_url_path"), disk.urlPath(VERIFIER, key, null, weird, "image/png", "inline"));
        assertSameDiskUrl(v.get("disk_url_path_nil_type"), disk.urlPath(VERIFIER, key, null, weird, null, "attachment"));

        String encodedKey = ((String) v.get("disk_url_path")).split("/")[4];
        var decoded = Disk.decodeVerifiedKey(VERIFIER, encodedKey, NOW);
        assertEquals(key, decoded.key());
        assertEquals("image/png", decoded.contentType());
        assertEquals("local", decoded.serviceName());

        String path = (String) v.get("direct_upload_path");
        String token = path.substring(path.lastIndexOf('/') + 1);
        var decodedToken = Disk.decodeVerifiedToken(VERIFIER, token, Instant.parse("2020-01-01T00:00:00Z"));
        assertEquals(42, decodedToken.contentLength());
        assertEquals("abc==", decodedToken.checksum());
        assertEquals(path, disk.directUploadPath(VERIFIER, key, Instant.parse("2026-09-26T12:45:14.841Z"), "image/png", 42, "abc=="));
        assertNull(Disk.decodeVerifiedToken(VERIFIER, token, Instant.parse("2026-09-26T12:45:14.841Z")), "expired");
    }

    /// What Rails (Marcel 1.1.0) stored for the same uploads.
    @Test
    void contentTypeIdentification() throws IOException {
        for (var sample : section("marcel")) {
            byte[] data = sample.get("fixture") instanceof String name ? Files.readAllBytes(FIXTURES.resolve(name))
                : HexFormat.of().parseHex((String) sample.get("data_hex"));
            byte[] head = java.util.Arrays.copyOf(data, Math.min(data.length, ContentTypeDetector.magicPrefixLength()));
            assertEquals(sample.get("content_type"), ContentTypeDetector.identify(head, (String) sample.get("name"), (String) sample.get("declared_type")),
                sample.get("name") + " declared " + sample.get("declared_type"));
        }
    }

    /// Marcel 1.1.0's types for what Campfire users upload, by name alone.
    @Test
    void contentTypesOfCommonUploads() {
        var expected = new java.util.LinkedHashMap<String, String>();
        expected.put("png", "image/png");
        expected.put("jpg", "image/jpeg");
        expected.put("jpeg", "image/jpeg");
        expected.put("gif", "image/gif");
        expected.put("webp", "image/webp");
        expected.put("heic", "image/heic");
        expected.put("heif", "image/heif");
        expected.put("avif", "image/avif");
        expected.put("tiff", "image/tiff");
        expected.put("svg", "image/svg+xml");
        expected.put("mp4", "video/mp4");
        expected.put("m4v", "video/mp4");
        expected.put("mov", "video/quicktime");
        expected.put("webm", "video/webm");
        expected.put("mkv", "video/x-matroska");
        expected.put("mp3", "audio/mpeg");
        expected.put("m4a", "audio/mp4");
        expected.put("aac", "audio/aac");
        expected.put("ogg", "audio/ogg");
        expected.put("opus", "audio/opus");
        expected.put("wav", "audio/x-wav");
        expected.put("flac", "audio/flac");
        expected.put("pdf", "application/pdf");
        expected.put("txt", "text/plain");
        expected.put("md", "text/markdown");
        expected.put("csv", "text/csv");
        expected.put("json", "application/json");
        expected.put("zip", "application/zip");
        expected.put("gz", "application/gzip");
        expected.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        expected.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        expected.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        expected.put("doc", "application/msword");
        expected.put("xls", "application/vnd.ms-excel");
        expected.put("key", "application/vnd.apple.keynote");
        expected.put("pages", "application/vnd.apple.pages");
        expected.put("numbers", "application/vnd.apple.numbers");
        var wrong = new ArrayList<String>();
        expected.forEach((ext, type) -> {
            String actual = ContentTypeDetector.identify(new byte[0], "upload." + ext, null);
            if (!type.equals(actual)) wrong.add(ext + ": " + actual + " instead of " + type);
        });
        assertEquals(List.of(), wrong);

        // By content alone, where Marcel's names differ from Tika's.
        assertEquals("audio/x-wav", ContentTypeDetector.identify(ascii("RIFF\0\0\0\0WAVEfmt "), "noext", null));
        assertEquals("audio/flac", ContentTypeDetector.identify(ascii("fLaC\0\0\0\""), "noext", null));
        assertEquals("audio/ogg", ContentTypeDetector.identify(ascii("OggS" + "\0".repeat(24) + "\u0001vorbis"), "noext", null));
        assertEquals("audio/mp4", ContentTypeDetector.identify(ascii("\0\0\0 ftypM4A \0\0\0\0"), "noext", null));
        assertEquals("video/mp4", ContentTypeDetector.identify(ascii("\0\0\0 ftypM4V \0\0\0\0"), "noext", null));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /// Rails' `I18n.transliterate` spells out `ß`, `Æ` and `œ` in the ASCII `filename`; this app
    /// writes `?` for every non-ASCII letter an accent can't be stripped from (`filename*` has
    /// the name).
    private static final Map<String, String> SPELLED_OUT = Map.of("filename=\"%3Fmega ss.txt\"", "filename=\"%3Fmega %3F.txt\"",
        "filename=\"AEther oeuvre.jpg\"", "filename=\"%3Fther %3Fuvre.jpg\"");

    private static String asciiFallback(Object recorded) {
        String disposition = (String) recorded;
        for (var spelled : SPELLED_OUT.entrySet()) disposition = disposition.replace(spelled.getKey(), spelled.getValue());
        return disposition;
    }

    @Test
    void filenamesAndDispositions() {
        for (var f : section("filenames")) {
            byte[] input = HexFormat.of().parseHex((String) f.get("input_hex"));
            var filename = new Filename(new String(input, StandardCharsets.UTF_8));
            String sanitized = filename.sanitized();
            assertEquals(f.get("sanitized"), sanitized);
            if (StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(input), java.nio.CharBuffer.allocate(input.length), true).isUnderflow()) {
                assertEquals(f.get("base_hex"), HexFormat.of().formatHex(filename.base().getBytes(StandardCharsets.UTF_8)), sanitized);
                assertEquals(f.get("extension_hex"), HexFormat.of().formatHex(filename.extension().getBytes(StandardCharsets.UTF_8)), sanitized);
            }
            assertEquals(f.get("escaped_path"), Urls.escapePath(sanitized));
            assertEquals(asciiFallback(f.get("inline")), Disk.contentDispositionWith("inline", sanitized));
            assertEquals(asciiFallback(f.get("attachment")), Disk.contentDispositionWith("attachment", sanitized));
        }
    }

    @Test
    void rubyFileSemantics() {
        String[][] cases = {{"foo.", ".", "foo"}, {"a.b.", ".", "a.b"}, {".bashrc", "", ".bashrc"}, {"..", "", ".."}, {"...", "", "..."},
            {".a.b", ".b", ".a"}, {"a..b", ".b", "a."}, {"a/b.c/d", "", "d"}, {"a.b/", ".b", "a"}, {"x.tar.gz", ".gz", "x.tar"}, {"a/.b", "", ".b"},
            {".", "", "."}, {"", "", ""}, {"é.png", ".png", "é"}, {"a. b", ". b", "a"}};
        for (String[] c : cases) {
            assertEquals(c[1], new Filename(c[0]).extensionWithDelimiter(), "extname " + c[0]);
            assertEquals(c[2], new Filename(c[0]).base(), "base " + c[0]);
        }
    }

    @Test
    void routePaths() {
        var storage = new Storage(Path.of("unused"), VERIFIER, null, 1 << 20);
        var disk = new Disk(Path.of("unused"), "local");
        for (var m : section("messages")) {
            Blob blob = blob(map(m.get("blob")));
            assertEquals(m.get("rails_blob_path"), storage.blobPath(blob));
            assertEquals(m.get("rails_blob_download_path"), storage.blobPath(blob, "attachment"));
            assertEquals(m.get("rails_blob_proxy_path"), Urls.blobProxyPath(VERIFIER, blob, null));
            assertEquals(blob.id(), Urls.verifySignedBlobId(VERIFIER, ((String) m.get("rails_blob_path")).split("/")[5], NOW));

            String forced = ContentTypes.forcedDisposition(blob.contentType());
            for (String[] expectation : new String[][] {{"inline", "service_url"}, {"attachment", "service_url_attachment"}}) {
                String disposition = forced != null ? forced : expectation[0];
                assertEquals(m.get(expectation[1]), "http://campfire.test" + disk.urlPath(VERIFIER, blob.key(), null, new Filename(blob.filename()),
                    ContentTypes.forServing(blob.contentType()), disposition));
            }

            assertEquals(m.get("variable"), storage.variable(blob), blob.filename());
            if (m.get("thumb_path") != null) {
                assertEquals(m.get("thumb_path"), storage.thumbPath(blob));
                Variation thumb = variation(map(((List<?>) m.get("variants")).getFirst()).get("transformations_typed"));
                assertEquals(m.get("thumb_proxy_path"), Urls.representationProxyPath(VERIFIER, blob, thumb));
            }
            if (m.get("poster_path") != null) {
                assertEquals(m.get("poster_path"), storage.previewPath(blob, Variation.VIDEO_POSTER));
                assertEquals(m.get("poster_proxy_path"), Urls.representationProxyPath(VERIFIER, blob, Variation.VIDEO_POSTER));
                if (Tools.FFMPEG.available()) assertEquals(m.get("poster_path"), storage.thumbPath(blob));
            }
        }
        assertNull(Urls.verifySignedBlobId(VERIFIER, "eyJfcmFpbHMiOnsiZGF0YSI6MSwicHVyIjoiYmxvYl9pZCJ9fQ==--0000", NOW));
    }

    @Test
    void previewDimensionsFollowThePresentation() {
        var messages = section("messages");
        var moon = Storage.previewDimensions(blob(map(messages.get(0).get("blob"))));
        assertEquals(640L, moon.width());
        assertEquals("320", moon.halfWidthCss());
        assertEquals("1.0", moon.aspectRatio());
        var blackHole = Storage.previewDimensions(blob(map(messages.get(2).get("blob"))));
        assertEquals(1200.0, blackHole.width());
        assertEquals(675.0, blackHole.height());
        assertEquals("600.0", blackHole.halfWidthCss());
        assertEquals("1.7777777777777777", blackHole.aspectRatio());
        var video = Storage.previewDimensions(blob(map(messages.get(3).get("blob"))));
        assertEquals("160.0", video.halfWidthCss());
        assertNull(Storage.previewDimensions(blob(map(messages.get(4).get("blob")))));
        assertEquals("1.0e+16", Storage.rubyFloat(1e16));
        assertEquals("0.0001", Storage.rubyFloat(1e-4));
        assertEquals("1.0e-05", Storage.rubyFloat(1e-5));
    }

    @Test
    void contentTypesForServing() {
        assertEquals("application/octet-stream", ContentTypes.forServing("text/html"));
        assertEquals("attachment", ContentTypes.forcedDisposition("video/quicktime"));
        assertNull(ContentTypes.forcedDisposition("image/png"));
        assertEquals("attachment", ContentTypes.forcedDisposition(null));
        assertFalse(ContentTypes.isVariable("image/bmp"));
        assertEquals("image/png", ContentTypeDetector.byExtension(".PNG"));
    }

    /// Single ranges as Rack reads them; null (the whole file) also for several ranges or a
    /// header that isn't one.
    @Test
    void byteRanges() {
        Object[][] cases = {
            {"bytes=0-4", 10L, "0-4"}, {"bytes=5-", 10L, "5-9"}, {"bytes=-3", 10L, "7-9"}, {"bytes=-30", 10L, "0-9"}, {"bytes=0-99", 10L, "0-9"},
            {"bytes=9-9", 10L, "9-9"}, {"Bytes= 0 - 1 ", 10L, "0-1"}, {"bytes=10-", 10L, "416"}, {"bytes=10-12", 10L, "416"},
            {"bytes=-0", 10L, "416"}, {"bytes=0-1, 3-4", 10L, null}, {"bytes=1-0", 10L, null}, {"bytes=-", 10L, null}, {"bytes=5", 10L, null},
            {"bytes=a-b", 10L, null}, {"items=0-1", 10L, null}, {"bytes=", 10L, null}, {"bytes=0-4", 0L, null},
            {"bytes=0-99999999999999999999", 10L, null},
        };
        for (Object[] c : cases) {
            ByteRange range = ByteRange.parse((String) c[0], (Long) c[1]);
            String actual = range == null ? null : range.satisfiable() ? range.first() + "-" + range.last() : "416";
            assertEquals(c[2], actual, (String) c[0]);
        }
        assertEquals("bytes 7-9/10", ByteRange.parse("bytes=-3", 10).contentRange(10));
    }

    @Test
    void marshalWritesBignumsAndSymlinks() {
        assertEquals("04086c2b0700000040", HexFormat.of().formatHex(RubyMarshal.dump(1L << 30)));
        assertEquals("04085b073a06613b00", HexFormat.of().formatHex(RubyMarshal.dump(List.of(new Sym("a"), new Sym("a")))));
        assertEquals("0408690200ff", HexFormat.of().formatHex(RubyMarshal.dump(65280L)));
        assertEquals("040869ff00", HexFormat.of().formatHex(RubyMarshal.dump(-256L)));
    }
}
