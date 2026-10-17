package campfire.storage;

import static campfire.db.schema.Tables.ACTIVE_STORAGE_ATTACHMENTS;
import static campfire.db.schema.Tables.ACTIVE_STORAGE_BLOBS;
import static campfire.db.schema.Tables.ACTIVE_STORAGE_VARIANT_RECORDS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.update;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.rails.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.Field;

/// A row of `active_storage_blobs`. `metadata` is the JSON text Active Storage keeps
/// (`identified`, `analyzed`, `width`, `height`, `duration`, `video`, ...).
public record Blob(long id, String key, String filename, String contentType, String metadata, String serviceName, long byteSize,
                   String checksum, Instant createdAt) {
    /// The columns {@link #from} reads, in its order.
    static final Field<?>[] COLUMNS = {ACTIVE_STORAGE_BLOBS.ID, ACTIVE_STORAGE_BLOBS.KEY, ACTIVE_STORAGE_BLOBS.FILENAME, ACTIVE_STORAGE_BLOBS.CONTENT_TYPE,
        ACTIVE_STORAGE_BLOBS.METADATA, ACTIVE_STORAGE_BLOBS.SERVICE_NAME, ACTIVE_STORAGE_BLOBS.BYTE_SIZE, ACTIVE_STORAGE_BLOBS.CHECKSUM,
        ACTIVE_STORAGE_BLOBS.CREATED_AT};

    static Blob from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    static Blob from(ResultSet rs, int offset) throws SQLException {
        return new Blob(rs.getLong(offset + 1), rs.getString(offset + 2), rs.getString(offset + 3), rs.getString(offset + 4),
            rs.getString(offset + 5), rs.getString(offset + 6), rs.getLong(offset + 7), rs.getString(offset + 8), Conn.getTime(rs, offset + 9));
    }

    /// The parsed metadata (empty when absent or malformed).
    public Map<String, Object> metadataMap() {
        if (metadata == null || metadata.isBlank()) return new LinkedHashMap<>();
        return Json.parseOrNull(metadata) instanceof Map<?, ?> m ? castMap(m) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    public Number metadataNumber(String name) {
        return metadataMap().get(name) instanceof Number n ? n : null;
    }

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }

    public boolean isVideo() {
        return contentType != null && contentType.startsWith("video/");
    }

    public boolean isAudio() {
        return contentType != null && contentType.startsWith("audio/");
    }

    private static final String FIND = Sql.render(select(COLUMNS).from(ACTIVE_STORAGE_BLOBS)
        .where(ACTIVE_STORAGE_BLOBS.ID.eq(param(ACTIVE_STORAGE_BLOBS.ID))).limit(inline(1)));
    private static final String FIND_BY_KEY = Sql.render(select(COLUMNS).from(ACTIVE_STORAGE_BLOBS)
        .where(ACTIVE_STORAGE_BLOBS.KEY.eq(param(ACTIVE_STORAGE_BLOBS.KEY))).limit(inline(1)));
    private static final String INSERT = Sql.render(insertInto(ACTIVE_STORAGE_BLOBS, ACTIVE_STORAGE_BLOBS.BYTE_SIZE, ACTIVE_STORAGE_BLOBS.CHECKSUM,
            ACTIVE_STORAGE_BLOBS.CONTENT_TYPE, ACTIVE_STORAGE_BLOBS.CREATED_AT, ACTIVE_STORAGE_BLOBS.FILENAME, ACTIVE_STORAGE_BLOBS.KEY,
            ACTIVE_STORAGE_BLOBS.METADATA, ACTIVE_STORAGE_BLOBS.SERVICE_NAME)
        .values(param(ACTIVE_STORAGE_BLOBS.BYTE_SIZE), param(ACTIVE_STORAGE_BLOBS.CHECKSUM), param(ACTIVE_STORAGE_BLOBS.CONTENT_TYPE),
            param(ACTIVE_STORAGE_BLOBS.CREATED_AT), param(ACTIVE_STORAGE_BLOBS.FILENAME), param(ACTIVE_STORAGE_BLOBS.KEY),
            param(ACTIVE_STORAGE_BLOBS.METADATA), inline("local")));
    private static final String UPDATE_METADATA = Sql.render(update(ACTIVE_STORAGE_BLOBS)
        .set(ACTIVE_STORAGE_BLOBS.METADATA, param(ACTIVE_STORAGE_BLOBS.METADATA)).where(ACTIVE_STORAGE_BLOBS.ID.eq(param(ACTIVE_STORAGE_BLOBS.ID))));
    private static final String DELETE = Sql.render(deleteFrom(ACTIVE_STORAGE_BLOBS).where(ACTIVE_STORAGE_BLOBS.ID.eq(param(ACTIVE_STORAGE_BLOBS.ID))));
    private static final String DELETE_VARIANT_ATTACHMENTS = Sql.render(deleteFrom(ACTIVE_STORAGE_ATTACHMENTS)
        .where(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE.eq(inline("ActiveStorage::VariantRecord")))
        .and(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID))));
    private static final String DELETE_VARIANT_RECORDS = Sql.render(deleteFrom(ACTIVE_STORAGE_VARIANT_RECORDS)
        .where(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID.eq(param(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID))));

    public static Blob find(Conn conn, long id) {
        return conn.one(FIND, Blob::from, id);
    }

    public static Blob findByKey(Conn conn, String key) {
        return conn.one(FIND_BY_KEY, Blob::from, key);
    }

    public static long create(Tx tx, String key, String filename, String contentType, String metadataJson, long byteSize, String checksum,
                              Instant now) {
        return tx.insert(INSERT, byteSize, checksum, contentType, now, filename, key, metadataJson);
    }

    public static void updateMetadata(Tx tx, long id, String metadataJson) {
        tx.update(UPDATE_METADATA, metadataJson, id);
    }

    /// The row alone; see {@link #deleteWithVariants} and `Storage.purge` for what goes with it.
    public static void delete(Tx tx, long id) {
        tx.update(DELETE, id);
    }

    /// Deletes the blob row and its variant records; returns their keys for the files to delete.
    public static List<String> deleteWithVariants(Tx tx, long id) {
        var keys = new java.util.ArrayList<String>();
        var blob = find(tx, id);
        if (blob == null) return keys;
        keys.add(blob.key());
        for (var variant : VariantRecord.forBlob(tx, id)) {
            var variantBlob = Attachment.findBlobFor(tx, "ActiveStorage::VariantRecord", variant.id(), "image");
            tx.update(DELETE_VARIANT_ATTACHMENTS, variant.id());
            if (variantBlob != null) {
                keys.add(variantBlob.key());
                delete(tx, variantBlob.id());
            }
        }
        tx.update(DELETE_VARIANT_RECORDS, id);
        // A preview image (video posters) is attached to the blob itself.
        for (var preview : Attachment.forRecord(tx, "ActiveStorage::Blob", id)) {
            Attachment.delete(tx, preview.id());
            keys.addAll(deleteWithVariants(tx, preview.blobId()));
        }
        delete(tx, id);
        return keys;
    }
}
