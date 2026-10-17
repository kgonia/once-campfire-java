package campfire.storage;

import static campfire.storage.StorageVectorsTest.FIXTURES;
import static campfire.storage.StorageVectorsTest.VERIFIER;
import static campfire.storage.StorageVectorsTest.map;
import static campfire.storage.StorageVectorsTest.section;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import campfire.db.Database;
import campfire.db.TestDatabase;
import campfire.rails.Json;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Uploads, analysis, variants, previews, purging and the endpoints' service over a temporary database
/// and disk. libvips- and ffmpeg-dependent parts are skipped when the tools aren't installed;
/// processed media is compared by dimensions and metadata, not bytes.
class StoragePipelineTest {
    @TempDir
    Path dir;
    TestDatabase database;
    Database db;
    Storage storage;
    ActiveStorageService service;

    @BeforeEach
    void setUp() {
        database = TestDatabase.open(dir.resolve("db.sqlite3"));
        db = database.db();
        storage = new Storage(dir.resolve("files"), VERIFIER, db, 16L * 1024 * 1024);
        service = new ActiveStorageService();
        service.storage = storage;
        service.db = db;
    }

    @AfterEach
    void tearDown() {
        storage.close();
        database.close();
    }

    Blob upload(String fixture, String filename, String declared) {
        return storage.createAndUpload(FIXTURES.resolve(fixture), filename, declared);
    }

    static void assertRow(Map<String, Object> expected, Blob actual, boolean comparePayload) {
        assertEquals(expected.get("filename"), actual.filename());
        assertEquals(expected.get("content_type"), actual.contentType(), actual.filename());
        assertEquals("local", actual.serviceName());
        assertEquals(28, actual.key().length());
        assertTrue(actual.key().matches("[0-9a-z]{28}"));
        if (comparePayload) {
            assertEquals(((Number) expected.get("byte_size")).longValue(), actual.byteSize(), actual.filename());
            assertEquals(expected.get("checksum"), actual.checksum(), actual.filename());
        }
    }

    @Test
    void uploadsAreIdentifiedChecksummedAndAnalyzedLikeRails() {
        for (var m : section("messages")) {
            var expected = map(m.get("blob"));
            String fixture = (String) m.get("fixture");
            Blob blob = upload(fixture, fixture, (String) m.get("declared_type"));
            assertRow(expected, blob, true);
            assertEquals("{\"identified\":true}", blob.metadata());
            String key = blob.key();
            assertEquals(dir.resolve("files").resolve(key.substring(0, 2)).resolve(key.substring(2, 4)).resolve(key), storage.pathFor(blob));
            assertTrue(Files.exists(storage.pathFor(blob)));

            if (blob.isVideo() ? !Tools.FFPROBE.available() : blob.isImage() && !Tools.VIPSHEADER.available()) continue;
            Blob analyzed = storage.analyze(blob);
            assertEquals(expected.get("metadata"), analyzed.metadata(), fixture);
        }
    }

    @Test
    void streamsUploadTheSameAsFiles() throws IOException {
        Blob fromFile = upload("moon.jpg", "moon.jpg", null);
        Blob fromStream = storage.createAndUpload(new ByteArrayInputStream(Files.readAllBytes(FIXTURES.resolve("moon.jpg"))), "moon.jpg", null);
        assertEquals(fromFile.checksum(), fromStream.checksum());
        assertEquals(fromFile.contentType(), fromStream.contentType());
        assertArrayEquals(Files.readAllBytes(storage.pathFor(fromFile)), Files.readAllBytes(storage.pathFor(fromStream)));
    }

    @Test
    void aStagedFileIsDeletedWhenItsRowIsNotSaved() {
        Path stagedPath;
        try (var staged = storage.stage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), "notes.txt", "text/plain")) {
            stagedPath = storage.pathFor(staged.key);
            assertTrue(Files.exists(stagedPath));
            assertEquals("text/plain", staged.contentType);
            assertThrows(IllegalStateException.class, () -> db.write(tx -> {
                storage.insert(tx, staged);
                throw new IllegalStateException("rolled back");
            }));
        }
        assertFalse(Files.exists(stagedPath));
    }

    @Test
    void imageVariantsMatchTheReference() throws IOException {
        assumeTrue(Tools.VIPS.available(), "libvips (vips) is not installed");
        for (var m : section("messages")) {
            String fixture = (String) m.get("fixture");
            Blob blob = upload(fixture, fixture, (String) m.get("declared_type"));
            if (!storage.variable(blob)) {
                assertNull(storage.processedVariant(blob, Variation.THUMB));
                continue;
            }
            var v = map(((List<?>) m.get("variants")).getFirst());
            Blob image = storage.processedVariant(blob, Variation.THUMB);
            assertVariant(blob, v, image);
            assertEquals(image.id(), storage.processedVariant(blob, Variation.THUMB).id(), "the variant record is reused");
        }
        for (var kind : List.of("avatars", "logos")) {
            for (var entry : section(kind)) {
                var row = map(entry.get("blob"));
                Blob blob = upload((String) entry.get("fixture"), (String) row.get("filename"), (String) row.get("content_type"));
                var variants = (List<?>) entry.get("variants");
                List<Variation> named = kind.equals("avatars") ? List.of(Variation.AVATAR_SQUARE) : List.of(Variation.LOGO_LARGE, Variation.LOGO_SMALL);
                for (int i = 0; i < variants.size(); i++) assertVariant(blob, map(variants.get(i)), storage.processedVariant(blob, named.get(i)));
            }
        }
    }

    void assertVariant(Blob source, Map<String, Object> v, Blob image) throws IOException {
        String label = (String) v.get("label");
        assertNotNull(image, label);
        var expected = map(v.get("blob"));
        assertRow(expected, image, false);
        assertEquals(expected.get("metadata"), image.metadata(), label);
        assertEquals(Disk.checksum(storage.pathFor(image)), image.checksum(), label);
        assertEquals(Files.size(storage.pathFor(image)), image.byteSize(), label);
        var record = db.read(conn -> VariantRecord.find(conn, source.id(), (String) v.get("variation_digest")));
        assertNotNull(record, label);
        assertEquals(image.id(), db.read(conn -> Attachment.findBlobFor(conn, "ActiveStorage::VariantRecord", record.id(), "image")).id());
    }

    @Test
    void concurrentRequestsForAVariantShareOneResult() throws Exception {
        assumeTrue(Tools.VIPS.available(), "libvips (vips) is not installed");
        Blob blob = upload("moon.jpg", "moon.jpg", null);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Blob>>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> storage.processedVariant(blob, Variation.THUMB)));
            long id = futures.getFirst().get().id();
            for (var f : futures) assertEquals(id, f.get().id());
        }
        assertEquals(1L, (long) db.read(conn -> conn.count("SELECT COUNT(*) FROM active_storage_variant_records")));
        assertEquals(2L, (long) db.read(conn -> conn.count("SELECT COUNT(*) FROM active_storage_blobs")));
    }

    @Test
    void videoPreviewsMatchTheReference() throws IOException {
        assumeTrue(Tools.FFMPEG.available(), "ffmpeg is not installed");
        assumeTrue(Tools.VIPSHEADER.available(), "libvips (vipsheader) is not installed");
        var m = section("messages").get(3);
        Blob video = upload("alpha-centuri.mov", "alpha-centuri.mov", "video/quicktime");
        assertTrue(storage.previewable(video));
        Blob preview = storage.previewImage(video);
        var expected = map(map(m.get("preview_image")).get("blob"));
        assertRow(expected, preview, false);
        assertEquals(expected.get("metadata"), preview.metadata());
        assertEquals(preview.id(), storage.previewImage(video).id());
        assertEquals(preview.id(), db.read(conn -> Attachment.findBlobFor(conn, "ActiveStorage::Blob", video.id(), "preview_image")).id());

        assumeTrue(Tools.VIPS.available(), "libvips (vips) is not installed");
        var variants = (List<?>) m.get("variants");
        assertVariant(preview, map(variants.get(0)), storage.processedPreview(video, Variation.VIDEO_PREVIEW));
        assertVariant(preview, map(variants.get(1)), storage.processedPreview(video, Variation.VIDEO_POSTER));
        Variation fromKey = Variation.decode(VERIFIER, Variation.VIDEO_POSTER.key(VERIFIER), StorageVectorsTest.NOW);
        assertVariant(preview, map(variants.get(2)), storage.processedRepresentation(video, fromKey));
    }

    @Test
    void processAttachmentDegradesWithoutTools() {
        Blob blob = upload("moon.jpg", "moon.jpg", "image/jpeg");
        storage.processAttachment(blob);
        Blob after = storage.find(blob.id());
        assertEquals(Tools.VIPSHEADER.available() ? "{\"identified\":true,\"width\":640,\"height\":640,\"analyzed\":true}"
            : "{\"identified\":true,\"analyzed\":true}", after.metadata());
        long variants = db.read(conn -> conn.count("SELECT COUNT(*) FROM active_storage_variant_records"));
        assertEquals(Tools.VIPS.available() ? 1 : 0, variants);
    }

    /// Analysis touches the records the blob is attached to through the toucher Storage was built with.
    @Test
    void analysisTouchesAttachedRecords() {
        var touched = new ArrayList<String>();
        try (var touching = new Storage(dir.resolve("files"), VERIFIER, db, 1 << 20, (tx, type, id) -> touched.add(type + " " + id))) {
            Blob blob = touching.createAndUpload(new ByteArrayInputStream("notes".getBytes(StandardCharsets.UTF_8)), "notes.txt",
                "text/plain");
            db.write(tx -> {
                Attachment.create(tx, "Message", 7, "attachment", blob.id(), campfire.db.Times.now());
                return null;
            });
            touching.analyze(blob);
        }
        assertEquals(List.of("Message 7"), touched);
    }

    @Test
    void purgeRemovesRowsFilesAndDependents() throws IOException {
        Blob blob = upload("moon.jpg", "moon.jpg", null);
        Path legacy = dir.resolve("files/va/ri/variants").resolve(blob.key()).resolve("old");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "x");
        // A fake processed variant, as Rails would have recorded it.
        Blob variant = storage.createAndUpload(new ByteArrayInputStream(new byte[] {1, 2, 3}), "moon.jpg", "image/jpeg");
        db.write(tx -> {
            long record = VariantRecord.create(tx, blob.id(), "digest");
            Attachment.create(tx, "ActiveStorage::VariantRecord", record, "image", variant.id(), campfire.db.Times.now());
            Attachment.create(tx, "Message", 1, "attachment", blob.id(), campfire.db.Times.now());
            return null;
        });
        assertFalse(storage.purge(blob.id()), "still attached");
        db.write(tx -> {
            Attachment.detach(tx, "Message", 1, "attachment");
            return null;
        });
        storage.purge(blob.id());
        assertNull(storage.find(blob.id()));
        assertNull(storage.find(variant.id()));
        assertFalse(Files.exists(storage.pathFor(blob)));
        assertFalse(Files.exists(storage.pathFor(variant)));
        assertFalse(Files.exists(legacy.getParent()));
        assertEquals(0L, (long) db.read(conn -> conn.count("SELECT COUNT(*) FROM active_storage_variant_records")));
        assertEquals(0L, (long) db.read(conn -> conn.count("SELECT COUNT(*) FROM active_storage_attachments")));
    }

    @Test
    void attachReplacesAndPurgesThePreviousBlob() throws Exception {
        Blob first = upload("moon.jpg", "moon.jpg", null);
        Blob second = upload("black_hole.jpg", "black_hole.jpg", null);
        db.write(tx -> {
            storage.attach(tx, "User", 7, "avatar", first);
            return null;
        });
        db.write(tx -> {
            storage.attach(tx, "User", 7, "avatar", second);
            return null;
        });
        assertEquals(second.id(), storage.attached("User", 7, "avatar").id());
        for (int i = 0; i < 200 && (storage.find(first.id()) != null || !Storage.analyzed(storage.find(second.id()))); i++) Thread.sleep(25);
        assertNull(storage.find(first.id()), "purged later");
        assertTrue(Storage.analyzed(storage.find(second.id())), "analyzed later");
    }

    // -- Endpoints -------------------------------------------------------------------------------

    @Test
    void blobRedirectNamesTheDiskFile() {
        Blob blob = upload("moon.jpg", "moon.jpg", null);
        String signedId = storage.blobPath(blob, "attachment").split("/")[5];
        assertEquals(blob.id(), service.blob(signedId).orElseThrow().id());
        String location = service.serviceUrl("https://campfire.test", blob, "attachment");
        assertTrue(location.startsWith("https://campfire.test/rails/active_storage/disk/"), location);
        String encodedKey = location.split("/")[6];
        Disk.Key key = service.diskKey(encodedKey).orElseThrow();
        assertEquals("attachment", key.disposition().split(";")[0]);
        assertEquals("image/jpeg", key.contentType());
        assertEquals("attachment; filename=\"moon.jpg\"; filename*=UTF-8''moon.jpg", key.disposition());
        assertEquals(storage.pathFor(blob), service.pathFor(key.key()));
        assertTrue(service.diskKey(encodedKey.replace("--", "--0")).isEmpty(), "forged");
        assertTrue(service.blob("nope").isEmpty());
    }

    @Test
    void representationsFallBackToTheOriginalWithoutVips() {
        Blob blob = upload("moon.jpg", "moon.jpg", null);
        String[] parts = storage.thumbPath(blob).split("/");
        Blob image = service.representation(service.blob(parts[5]).orElseThrow(), parts[6]).orElseThrow();
        if (Tools.VIPS.available()) assertFalse(image.key().equals(blob.key()));
        else assertEquals(blob.key(), image.key());
        assertTrue(service.representation(blob, "garbage").isEmpty());
    }

    @Test
    void directUploadsCreateTheBlobAndAcceptTheBytes() {
        byte[] data = "hello direct upload".getBytes(StandardCharsets.UTF_8);
        String checksum = campfire.rails.Base64s.strictEncode(Disk.md5().digest(data));
        var json = service.createDirectUpload("https://campfire.test", "hello.txt", "text/plain", Map.of(), data.length, checksum).json();
        assertEquals(List.of("id", "byte_size", "checksum", "content_type", "created_at", "filename", "key", "metadata", "service_name", "signed_id",
            "direct_upload"), new ArrayList<>(json.keySet()));
        assertEquals((long) data.length, json.get("byte_size"));
        assertEquals(Map.of(), json.get("metadata"));
        assertTrue(((String) json.get("created_at")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
        var directUpload = (Map<?, ?>) json.get("direct_upload");
        assertEquals(Map.of("Content-Type", "text/plain"), directUpload.get("headers"));
        String url = (String) directUpload.get("url");
        assertTrue(url.startsWith("https://campfire.test/rails/active_storage/disk/"), url);
        Disk.Token token = service.uploadToken(url.substring(url.lastIndexOf('/') + 1)).orElseThrow();
        assertEquals(data.length, token.contentLength());
        Blob blob = service.blob((String) json.get("signed_id")).orElseThrow();
        assertEquals("hello.txt", blob.filename());

        var tampered = data.clone();
        tampered[0] = 'H';
        assertFalse(service.upload(token, tampered));
        assertFalse(Files.exists(storage.pathFor(blob)));
        assertTrue(service.upload(token, data));
        assertEquals(checksum, Disk.checksum(storage.pathFor(blob)));
    }

    @Test
    void metadataKeepsRubysKeyOrderOnReanalysis() {
        assumeTrue(Tools.VIPSHEADER.available(), "libvips (vipsheader) is not installed");
        Blob blob = upload("moon.jpg", "moon.jpg", null);
        db.write(tx -> {
            Blob.updateMetadata(tx, blob.id(), "{\"identified\":true,\"height\":1,\"custom\":\"x\"}");
            return null;
        });
        Blob analyzed = storage.analyze(storage.find(blob.id()));
        var expected = new LinkedHashMap<String, Object>();
        expected.put("identified", true);
        expected.put("height", 640L);
        expected.put("custom", "x");
        expected.put("width", 640L);
        expected.put("analyzed", true);
        assertEquals(Json.encode(expected), analyzed.metadata());
    }
}
