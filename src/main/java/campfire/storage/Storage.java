package campfire.storage;

import static campfire.db.schema.Tables.ACTIVE_STORAGE_BLOBS;
import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;

import campfire.Config;
import campfire.db.Conn;
import campfire.db.Database;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import campfire.storage.Attachment;
import campfire.storage.Blob;
import campfire.storage.VariantRecord;
import campfire.rails.Json;
import campfire.rails.Messages;
import campfire.rails.MessageVerifier;
import campfire.rails.Secrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/// Active Storage for Campfire over the Rails schema and disk layout: uploads
/// (`Blob.create_and_upload!`), analysis, tracked variants (`VariantWithRecord`), video previews
/// (`ActiveStorage::Preview`), purging, the route helpers, and the engine's controllers.
///
/// Slow work (copying, checksumming, libvips, ffmpeg, ffprobe) never holds the database writer:
/// it runs first, at most {@link #MAX_MEDIA_JOBS} at a time, and the rows are written after. Two
/// requests for the same variant or preview share one run; across processes the first row saved
/// wins and the loser's file is deleted.
public final class Storage implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Storage.class.getName());

    public static final String SERVICE_NAME = "local";
    /// `ActiveStorage.service_urls_expire_in`.
    public static final Duration SERVICE_URLS_EXPIRE_IN = Duration.ofMinutes(5);
    /// The most image and video jobs (variants, previews, analysis) that run at once.
    public static final int MAX_MEDIA_JOBS = 4;
    /// `ActiveStorage::Blob::MINIMUM_TOKEN_LENGTH` (`has_secure_token :key, length: 28`).
    public static final int KEY_LENGTH = 28;

    static final String VARIANT_RECORD = "ActiveStorage::VariantRecord";
    static final String BLOB_RECORD = "ActiveStorage::Blob";

    /// Touches a record whose attachment's blob changed (`Blob#touch_attachments` with
    /// `touch_attachment_records`), e.g. a message and, through `touch: true`, its room.
    @FunctionalInterface
    public interface RecordToucher {
        void touch(Tx tx, String recordType, long recordId);
    }

    private final Disk disk;
    private final MessageVerifier verifier;
    private final Database db;
    private final long maxDirectUploadBytes;
    private final Semaphore mediaJobs =
        new Semaphore(Math.clamp(Runtime.getRuntime().availableProcessors(), 1, MAX_MEDIA_JOBS));
    private final ConcurrentHashMap<String, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService later = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("campfire-storage-", 0).factory());
    private final SecureRandom random = new SecureRandom();
    private volatile RecordToucher toucher = (tx, type, id) -> {};

    public Storage(Config config, Secrets secrets, Database db) {
        this(config.filesPath(), secrets.appVerifier("ActiveStorage"), db, config.maxRequestBody());
    }

    /// `verifier` is `ActiveStorage.verifier` (`Rails.application.message_verifier("ActiveStorage")`).
    public Storage(Path filesRoot, MessageVerifier verifier, Database db, long maxDirectUploadBytes) {
        this.disk = new Disk(filesRoot, SERVICE_NAME);
        this.verifier = verifier;
        this.db = db;
        this.maxDirectUploadBytes = maxDirectUploadBytes;
    }

    public void onTouch(RecordToucher toucher) {
        this.toucher = toucher;
    }

    /// The largest direct upload to hand out a URL for: its PUT body is read into memory.
    long maxDirectUploadBytes() {
        return maxDirectUploadBytes;
    }

    public Disk disk() {
        return disk;
    }

    public MessageVerifier verifier() {
        return verifier;
    }

    public Path pathFor(String key) {
        return disk.pathFor(key);
    }

    public Path pathFor(Blob blob) {
        return disk.pathFor(blob.key());
    }

    @Override
    public void close() {
        later.shutdown();
    }

    // -- Lookups and predicates ------------------------------------------------------------------

    public Blob find(long id) {
        return db.read(conn -> Blob.find(conn, id));
    }

    /// The blob `record` has attached as `name` (`has_one_attached`), or null.
    public Blob attached(String recordType, long recordId, String name) {
        return db.read(conn -> Attachment.findBlobFor(conn, recordType, recordId, name));
    }

    /// `ActiveStorage::Blob.find_signed(signed_id)`, or null.
    public Blob findSigned(String signedId) {
        Long id = Urls.verifySignedBlobId(verifier, signedId, Instant.now());
        return id == null ? null : find(id);
    }

    public static boolean isImage(Blob blob) {
        return blob.contentType() != null && blob.contentType().startsWith("image");
    }

    public static boolean isVideo(Blob blob) {
        return blob.contentType() != null && blob.contentType().startsWith("video");
    }

    /// `variable?`
    public boolean variable(Blob blob) {
        return ContentTypes.isVariable(blob.contentType());
    }

    /// `previewable?`: only the video previewer can accept (the image has no poppler or mupdf, so
    /// the PDF previewers never do).
    public boolean previewable(Blob blob) {
        return Transformer.canPreview(blob.contentType());
    }

    /// `representable?`
    public boolean representable(Blob blob) {
        return variable(blob) || previewable(blob);
    }

    public static boolean analyzed(Blob blob) {
        Object value = blob.metadataMap().get("analyzed");
        return value != null && !Boolean.FALSE.equals(value);
    }

    /// `blob.variant(transformations)`'s variation: defaulted to the blob's variant format.
    public Variation variationFor(Blob blob, Variation transformations) {
        if (!variable(blob)) throw new StorageException("can't transform blob with content_type=" + blob.contentType());
        return transformations.defaultTo(Map.of("format", defaultVariantFormat(blob)));
    }

    /// `default_variant_format`: web images keep their format, everything else becomes PNG.
    static String defaultVariantFormat(Blob blob) {
        if (!ContentTypes.isWebImage(blob.contentType())) return "png";
        // `Representable#format`: the extension when Marcel agrees it names the content type.
        String extension = new Filename(blob.filename()).extension();
        if (!extension.isEmpty() && Marcel.forExtension(extension).equals(blob.contentType())) return extension;
        List<String> extensions = Marcel.extensions(blob.contentType());
        return extensions.isEmpty() ? "png" : extensions.getFirst();
    }

    // -- Paths -------------------------------------------------------------------------------------

    /// `rails_blob_path(blob)`.
    public String blobPath(Blob blob) {
        return Urls.blobRedirectPath(verifier, blob, null);
    }

    /// `rails_blob_path(blob, disposition:)`, e.g. "attachment" for downloads.
    public String blobPath(Blob blob, String disposition) {
        return Urls.blobRedirectPath(verifier, blob, disposition);
    }

    /// `url_for(blob.variant(transformations))`: the format defaults as `variant` does.
    public String variantPath(Blob blob, Variation transformations) {
        return Urls.representationRedirectPath(verifier, blob, variationFor(blob, transformations));
    }

    /// `url_for(blob.preview(transformations))`: the variation exactly as given.
    public String previewPath(Blob blob, Variation transformations) {
        return Urls.representationRedirectPath(verifier, blob, transformations);
    }

    /// The message attachment's thumbnail (`Messages::AttachmentPresentation`): the video poster
    /// (`preview(format: :webp, resize_to_limit: [1200, 800])`) or `representation(:thumb)`; null
    /// when the attachment is shown as a file link.
    public String thumbPath(Blob blob) {
        if (isVideo(blob)) return previewable(blob) ? previewPath(blob, Variation.VIDEO_POSTER) : null;
        return variable(blob) ? variantPath(blob, Variation.THUMB) : null;
    }

    /// `blob.url(disposition:)` on the disk service: a signed `/rails/active_storage/disk/...` URL
    /// on `baseUrl` (the request's protocol and host) that expires in five minutes.
    public String serviceUrl(String baseUrl, Blob blob, String disposition) {
        String forced = ContentTypes.forcedDisposition(blob.contentType());
        return baseUrl + disk.urlPath(verifier, blob.key(), Instant.now().plus(SERVICE_URLS_EXPIRE_IN), new Filename(blob.filename()),
            ContentTypes.forServing(blob.contentType()), forced != null ? forced : disposition);
    }

    /// `Messages::AttachmentPresentation#preview_dimensions`: the metadata's width and height,
    /// scaled down to fit 1200×800. Numbers stay Ruby-typed: `Long` for images, `Double` for
    /// videos and scaled sizes.
    public record Dimensions(Number width, Number height) {
        /// `width / 2` as the view interpolates it (integer division for integers).
        public String halfWidthCss() {
            return width instanceof Double d ? rubyFloat(d / 2) : Long.toString(Math.floorDiv(width.longValue(), 2));
        }

        /// `width / height.to_f`.
        public String aspectRatio() {
            return rubyFloat(width.doubleValue() / height.doubleValue());
        }

        public String widthText() {
            return width instanceof Double d ? rubyFloat(d) : width.toString();
        }
    }

    public static final int THUMBNAIL_MAX_WIDTH = 1200;
    public static final int THUMBNAIL_MAX_HEIGHT = 800;

    /// Null when the metadata has no width or height.
    public static Dimensions previewDimensions(Blob blob) {
        Number width = rubyNumber(blob.metadataNumber("width"));
        Number height = rubyNumber(blob.metadataNumber("height"));
        if (width == null || height == null) return null;
        if (width.doubleValue() <= THUMBNAIL_MAX_WIDTH && height.doubleValue() <= THUMBNAIL_MAX_HEIGHT) return new Dimensions(width, height);
        double scale = Math.min((double) THUMBNAIL_MAX_WIDTH / width.doubleValue(), (double) THUMBNAIL_MAX_HEIGHT / height.doubleValue());
        return new Dimensions(width.doubleValue() * scale, height.doubleValue() * scale);
    }

    private static Number rubyNumber(Number n) {
        return switch (n) {
            case null -> null;
            case Double d -> d;
            case Float f -> (double) f;
            default -> n.longValue();
        };
    }

    /// Ruby's `Float#to_s`.
    public static String rubyFloat(double d) {
        if (Double.isNaN(d)) return "NaN";
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";
        if (d == 0) return 1 / d < 0 ? "-0.0" : "0.0";
        double abs = Math.abs(d);
        if (abs >= 1e-4 && abs < 1e16) {
            String plain = new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        var decimal = new BigDecimal(Double.toString(abs)).stripTrailingZeros();
        String digits = decimal.unscaledValue().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        String mantissa = digits.charAt(0) + "." + (digits.length() > 1 ? digits.substring(1) : "0");
        return (d < 0 ? "-" : "") + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
    }

    // -- Uploads -----------------------------------------------------------------------------------

    /// A new blob whose file is already in place but whose row may not be saved yet. Closing it
    /// deletes the file unless {@link #insert} committed its row (or {@link #keep} was called), so
    /// try-with-resources around the write leaves no orphan when the transaction rolls back.
    public static final class Staged implements AutoCloseable {
        private final Disk disk;
        public final String key;
        public final String filename;
        public final String contentType;
        private final Map<String, Object> metadata;
        public final long byteSize;
        public final String checksum;
        private volatile boolean kept;

        Staged(Disk disk, String key, String filename, String contentType, Map<String, Object> metadata, long byteSize, String checksum) {
            this.disk = disk;
            this.key = key;
            this.filename = filename;
            this.contentType = contentType;
            this.metadata = metadata;
            this.byteSize = byteSize;
            this.checksum = checksum;
        }

        public String metadataJson() {
            return Json.encode(metadata);
        }

        public void keep() {
            kept = true;
        }

        @Override
        public void close() {
            if (!kept) disk.delete(key);
        }
    }

    /// The file half of `Blob.create_and_upload!(io:, filename:, content_type:)` as attaching an
    /// upload does: a new key, the file copied into place and checksummed, the content type
    /// identified with Marcel, metadata `{"identified":true}`.
    public Staged stage(Path source, String filename, String declaredContentType) {
        try (InputStream in = Files.newInputStream(source)) {
            return stage(in, filename, declaredContentType);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Staged stage(InputStream in, String filename, String declaredContentType) {
        String key = generateKey();
        Path path = disk.pathFor(key);
        MessageDigest md5 = Disk.md5();
        int prefix = Marcel.magicPrefixLength();
        var head = new ByteArrayOutputStream(Math.min(prefix, 64 * 1024));
        long size = 0;
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    md5.update(buffer, 0, n);
                    if (head.size() < prefix) head.write(buffer, 0, Math.min(n, prefix - head.size()));
                    out.write(buffer, 0, n);
                    size += n;
                }
            }
        } catch (IOException e) {
            disk.delete(key);
            throw new UncheckedIOException(e);
        }
        String contentType = Marcel.identify(head.toByteArray(), new Filename(filename).sanitized(), declaredContentType);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("identified", true);
        return new Staged(disk, key, filename, contentType, metadata, size, campfire.rails.Base64s.strictEncode(md5.digest()));
    }

    /// Saves a staged blob's row; the file is kept once the transaction commits.
    public Blob insert(Tx tx, Staged staged) {
        long id = Blob.create(tx, staged.key, staged.filename, staged.contentType, staged.metadataJson(), staged.byteSize, staged.checksum, Times.now());
        tx.afterCommit(staged::keep);
        return Blob.find(tx, id);
    }

    /// `ActiveStorage::Blob.create_and_upload!(io:, filename:, content_type:)`.
    public Blob createAndUpload(Path source, String filename, String declaredContentType) {
        try (Staged staged = stage(source, filename, declaredContentType)) {
            return db.write(tx -> insert(tx, staged));
        }
    }

    public Blob createAndUpload(InputStream in, String filename, String declaredContentType) {
        try (Staged staged = stage(in, filename, declaredContentType)) {
            return db.write(tx -> insert(tx, staged));
        }
    }

    /// `record.<name>.attach(blob)` for `has_one_attached`: replaces the current attachment (its
    /// blob is purged later) and, once committed, analyzes the blob later unless it already is.
    /// Touching `record` is the caller's.
    public void attach(Tx tx, String recordType, long recordId, String name, Blob blob) {
        List<Long> replaced = detach(tx, recordType, recordId, name);
        replaced.remove(Long.valueOf(blob.id()));
        Attachment.create(tx, recordType, recordId, name, blob.id(), Times.now());
        boolean needsAnalysis = !analyzed(blob);
        tx.afterCommit(() -> {
            replaced.forEach(this::purgeLater);
            if (needsAnalysis) analyzeLater(blob.id());
        });
    }

    /// `record.<name>.destroy` / `purge_later`: removes the attachment and purges its blob once
    /// committed.
    public void detachAndPurgeLater(Tx tx, String recordType, long recordId, String name) {
        List<Long> blobIds = detach(tx, recordType, recordId, name);
        tx.afterCommit(() -> blobIds.forEach(this::purgeLater));
    }

    private static List<Long> detach(Tx tx, String recordType, long recordId, String name) {
        var blobIds = new ArrayList<Long>();
        for (Attachment attachment : Attachment.forRecord(tx, recordType, recordId)) {
            if (!attachment.name().equals(name)) continue;
            Attachment.delete(tx, attachment.id());
            blobIds.add(attachment.blobId());
        }
        return blobIds;
    }

    /// `SecureRandom.base36(28)`.
    String generateKey() {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        var key = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) key.append(alphabet.charAt(random.nextInt(36)));
        return key.toString();
    }

    // -- Analysis ----------------------------------------------------------------------------------

    /// `blob.analyze`: `update!(metadata: metadata.merge(analyzer.metadata.merge(analyzed: true)))`,
    /// then the attachments' records are touched. Returns the updated blob (null if it's gone).
    public Blob analyze(Blob blob) {
        Map<String, Object> extracted = once("analyze:" + blob.id(), () -> media(() -> {
            String type = blob.contentType() == null ? "" : blob.contentType();
            boolean readsFile = type.startsWith("image") || type.startsWith("video") || type.startsWith("audio");
            return readsFile ? Analyzer.metadata(type, verifiedFile(blob)) : new LinkedHashMap<>();
        }));
        return db.write(tx -> {
            Blob current = Blob.find(tx, blob.id());
            if (current == null) return null;
            Map<String, Object> metadata = current.metadataMap();
            metadata.putAll(extracted);
            metadata.put("analyzed", true);
            Blob.updateMetadata(tx, blob.id(), Json.encode(metadata));
            for (Attachment a : attachmentsOf(tx, blob.id())) toucher.touch(tx, a.recordType(), a.recordId());
            return Blob.find(tx, blob.id());
        });
    }

    /// `analyze_later`: analyzes off this thread unless the blob is analyzed by then.
    public void analyzeLater(long blobId) {
        runLater("analyze blob " + blobId, () -> {
            Blob blob = find(blobId);
            if (blob != null && !analyzed(blob)) analyze(blob);
        });
    }

    private static List<Attachment> attachmentsOf(Conn conn, long blobId) {
        return Attachment.forBlob(conn, blobId);
    }

    // -- Variants and previews ---------------------------------------------------------------------

    /// `blob.variant(transformations).processed`: the variant's image blob, transformed now if
    /// it doesn't exist yet. Null when the blob isn't variable or processing failed (logged), so
    /// callers fall back (avatars to initials, logos to the stock icon).
    public Blob processedVariant(Blob blob, Variation transformations) {
        if (!variable(blob)) return null;
        try {
            return variant(blob, variationFor(blob, transformations));
        } catch (StorageException.Unavailable e) {
            LOG.fine(e::getMessage);
            return null;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "variant of blob " + blob.id() + " failed", e);
            return null;
        }
    }

    /// `blob.preview(transformations).processed`: the preview image itself for empty
    /// transformations, otherwise its variant. Throws when the blob isn't previewable or a tool fails.
    public Blob processedPreview(Blob blob, Variation transformations) {
        Blob image = previewImage(blob);
        if (transformations.isEmpty()) return image;
        return variant(image, variationFor(image, transformations));
    }

    /// `blob.representation(transformations).processed`: a preview for previewable blobs, a
    /// variant for variable ones; throws otherwise (`ActiveStorage::UnrepresentableError`).
    public Blob processedRepresentation(Blob blob, Variation transformations) {
        if (previewable(blob)) return processedPreview(blob, transformations);
        if (variable(blob)) return variant(blob, variationFor(blob, transformations));
        throw new StorageException("no previewer found and can't transform blob with content_type=" + blob.contentType());
    }

    /// `VariantWithRecord#processed` for an already-defaulted variation.
    Blob variant(Blob blob, Variation variation) {
        String digest = variation.digest();
        Blob existing = db.read(conn -> VariantRecord.processedBlob(conn, blob.id(), digest));
        if (existing != null) return existing;
        return once("variant:" + blob.id() + ":" + digest, () -> {
            Blob again = db.read(conn -> VariantRecord.processedBlob(conn, blob.id(), digest));
            if (again != null) return again;
            try (Staged image = media(() -> transformVariant(blob, variation))) {
                // `variant_records.create_or_find_by!(variation_digest:) { |record| record.image.attach(image) }`
                return db.write(tx -> {
                    var record = VariantRecord.find(tx, blob.id(), digest);
                    if (record != null) {
                        Blob recorded = Attachment.findBlobFor(tx, VARIANT_RECORD, record.id(), "image");
                        if (recorded != null) return recorded;
                    }
                    long recordId = record != null ? record.id() : VariantRecord.create(tx, blob.id(), digest);
                    Blob saved = insert(tx, image);
                    Attachment.create(tx, VARIANT_RECORD, recordId, "image", saved.id(), Times.now());
                    return saved;
                });
            }
        });
    }

    /// Transforms the blob and stages the image as `<base>.<format>`, already analyzed (Rails
    /// analyzes it in an `AnalyzeJob` after commit).
    private Staged transformVariant(Blob blob, Variation variation) {
        return inTempDir(dir -> {
            Path output = Transformer.transform(verifiedFile(blob), variation, dir);
            String filename = new Filename(blob.filename()).base() + "." + variation.format().toLowerCase(Locale.ROOT);
            return stageAnalyzed(output, filename, variation.contentType());
        });
    }

    /// `blob.preview_image`, drawn with ffmpeg and attached when missing (`Preview#process`).
    public Blob previewImage(Blob blob) {
        Blob existing = attached(BLOB_RECORD, blob.id(), "preview_image");
        if (existing != null) return existing;
        if (!previewable(blob)) throw new StorageException("no previewer found for content_type=" + blob.contentType());
        return once("preview:" + blob.id(), () -> {
            Blob again = attached(BLOB_RECORD, blob.id(), "preview_image");
            if (again != null) return again;
            try (Staged image = media(() -> inTempDir(dir -> stageAnalyzed(Transformer.videoPreview(verifiedFile(blob), dir),
                new Filename(blob.filename()).base() + ".jpg", "image/jpeg")))) {
                return db.write(tx -> {
                    Blob recorded = Attachment.findBlobFor(tx, BLOB_RECORD, blob.id(), "preview_image");
                    if (recorded != null) return recorded;
                    Blob saved = insert(tx, image);
                    Attachment.create(tx, BLOB_RECORD, blob.id(), "preview_image", saved.id(), Times.now());
                    return saved;
                });
            }
        });
    }

    /// Stages a generated image with the metadata its analysis would save.
    private Staged stageAnalyzed(Path file, String filename, String contentType) {
        Staged staged = stage(file, filename, contentType);
        staged.metadata.putAll(Analyzer.metadata(staged.contentType, file));
        staged.metadata.put("analyzed", true);
        return staged;
    }

    /// `Message::Attachment#process_attachment`: analyze, then make the thumbnail (videos:
    /// `preview(format: :webp)`; other representable blobs: `representation(:thumb)`). Failures
    /// are logged; without libvips/ffmpeg the views fall back to the original.
    public void processAttachment(Blob blob) {
        try {
            Blob analyzedBlob = analyze(blob);
            if (analyzedBlob == null) return;
            if (isVideo(analyzedBlob)) {
                if (previewable(analyzedBlob)) processedPreview(analyzedBlob, Variation.VIDEO_PREVIEW);
            } else if (representable(analyzedBlob)) {
                processedRepresentation(analyzedBlob, Variation.THUMB);
            }
        } catch (StorageException.Unavailable e) {
            LOG.fine(e::getMessage);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "processing attachment blob " + blob.id() + " failed", e);
        }
    }

    // -- Purging -----------------------------------------------------------------------------------

    /// `ActiveStorage::Blob#purge`: refused (false) while an attachment still points at the blob
    /// (`before_destroy { raise ActiveRecord::InvalidForeignKey if attachments.exists? }`).
    /// Otherwise the row, its variant records and preview image attachment go, then its file
    /// (and legacy `variants/<key>/` folder), then the variant and preview blobs the same way.
    public boolean purge(long blobId) {
        var dependents = new ArrayList<Long>();
        Blob purged = db.write(tx -> {
            Blob blob = Blob.find(tx, blobId);
            if (blob == null || Attachment.blobAttachedElsewhere(tx, blobId)) return null;
            for (VariantRecord record : VariantRecord.forBlob(tx, blobId)) {
                dependents.addAll(detach(tx, VARIANT_RECORD, record.id(), "image"));
                VariantRecord.delete(tx, record.id());
            }
            dependents.addAll(detach(tx, BLOB_RECORD, blobId, "preview_image"));
            Blob.delete(tx, blobId);
            return blob;
        });
        if (purged == null) return false;
        disk.delete(purged.key());
        if (isImage(purged)) disk.deleteLegacyVariants(purged.key());
        for (long dependent : dependents) purge(dependent);
        return true;
    }

    public void purgeLater(long blobId) {
        runLater("purge blob " + blobId, () -> purge(blobId));
    }


    /// `*` rather than the generated columns: `as_json` has every column the database holds.
    private static final String BLOB_AS_JSON = Sql.render(select(asterisk()).from(ACTIVE_STORAGE_BLOBS)
        .where(ACTIVE_STORAGE_BLOBS.ID.eq(param(ACTIVE_STORAGE_BLOBS.ID))));

    /// `blob.as_json`: every column in table order, times as ISO 8601 with milliseconds.
    static Map<String, Object> asJson(Conn conn, long id) {
        return conn.one(BLOB_AS_JSON, rs -> {
            var json = new LinkedHashMap<String, Object>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String column = meta.getColumnName(i);
                Object value = switch (column) {
                    case "id", "byte_size" -> rs.getLong(i);
                    case "created_at" -> {
                        Instant t = Times.parseDb(rs.getString(i));
                        yield t == null ? rs.getString(i) : Messages.iso8601Millis(t);
                    }
                    case "metadata" -> {
                        String text = rs.getString(i);
                        Object parsed = text == null ? null : Json.parseOrNull(text);
                        yield parsed instanceof Map<?, ?> ? parsed : new LinkedHashMap<>();
                    }
                    default -> rs.getString(i);
                };
                json.put(column, value);
            }
            return json;
        }, id);
    }

    // -- Plumbing ----------------------------------------------------------------------------------

    /// `blob.open`'s integrity check, without the copy: the stored file, if its checksum matches.
    private Path verifiedFile(Blob blob) {
        Path path = disk.pathFor(blob.key());
        if (!Files.isRegularFile(path)) throw new StorageException("file not found for blob " + blob.id());
        if (blob.checksum() != null && !blob.checksum().equals(Disk.checksum(path))) {
            throw new StorageException("checksum mismatch for blob " + blob.id());
        }
        return path;
    }

    @FunctionalInterface
    private interface InDir<T> {
        T run(Path dir);
    }

    private static <T> T inTempDir(InDir<T> work) {
        Path dir;
        try {
            dir = Files.createTempDirectory("ActiveStorage-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return work.run(dir);
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (IOException e) {
                LOG.fine(() -> "can't clean " + dir + ": " + e.getMessage());
            }
        }
    }

    /// Runs libvips, ffmpeg or ffprobe work a few jobs at a time: each can take a lot of memory
    /// and CPU (libvips threads its own work).
    private <T> T media(Supplier<T> work) {
        try {
            mediaJobs.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("interrupted waiting for a media job slot", e);
        }
        try {
            return work.get();
        } finally {
            mediaJobs.release();
        }
    }

    /// Runs `work` once for concurrent callers with the same `key`; the others wait for its result.
    @SuppressWarnings("unchecked")
    private <T> T once(String key, Supplier<T> work) {
        var mine = new CompletableFuture<Object>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return (T) running.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                throw e;
            }
        }
        try {
            T value = work.get();
            mine.complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private void runLater(String what, Runnable work) {
        try {
            later.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, what + " failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.warning("storage is shut down; dropped: " + what);
        }
    }
}
