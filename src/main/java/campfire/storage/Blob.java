package campfire.storage;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.rails.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A row of `active_storage_blobs`. `metadata` is the JSON text Active Storage keeps
/// (`identified`, `analyzed`, `width`, `height`, `duration`, `video`, ...).
public record Blob(long id, String key, String filename, String contentType, String metadata, String serviceName, long byteSize,
                   String checksum, Instant createdAt) {
    public static final String COLUMNS = "\"active_storage_blobs\".\"id\", \"active_storage_blobs\".\"key\", \"active_storage_blobs\".\"filename\", "
        + "\"active_storage_blobs\".\"content_type\", \"active_storage_blobs\".\"metadata\", \"active_storage_blobs\".\"service_name\", "
        + "\"active_storage_blobs\".\"byte_size\", \"active_storage_blobs\".\"checksum\", \"active_storage_blobs\".\"created_at\"";
    public static final String SELECT = "SELECT " + COLUMNS + " FROM \"active_storage_blobs\"";
    public static final int COLUMN_COUNT = 9;

    public static Blob from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    public static Blob from(ResultSet rs, int offset) throws SQLException {
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

    public static Blob find(Conn conn, long id) {
        return conn.one(SELECT + " WHERE \"active_storage_blobs\".\"id\" = ? LIMIT 1", Blob::from, id);
    }

    public static Blob findByKey(Conn conn, String key) {
        return conn.one(SELECT + " WHERE \"active_storage_blobs\".\"key\" = ? LIMIT 1", Blob::from, key);
    }

    public static long create(Tx tx, String key, String filename, String contentType, String metadataJson, long byteSize, String checksum,
                              Instant now) {
        return tx.insert("INSERT INTO \"active_storage_blobs\" (\"byte_size\", \"checksum\", \"content_type\", \"created_at\", \"filename\", \"key\","
            + " \"metadata\", \"service_name\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)", byteSize, checksum, contentType, now, filename, key, metadataJson, "local");
    }

    public static void updateMetadata(Tx tx, long id, String metadataJson) {
        tx.update("UPDATE \"active_storage_blobs\" SET \"metadata\" = ? WHERE \"id\" = ?", metadataJson, id);
    }

    /// Deletes the blob row and its variant records; returns their keys for the files to delete.
    public static List<String> deleteWithVariants(Tx tx, long id) {
        var keys = new java.util.ArrayList<String>();
        var blob = find(tx, id);
        if (blob == null) return keys;
        keys.add(blob.key());
        for (var variant : VariantRecord.forBlob(tx, id)) {
            var variantBlob = Attachment.findBlobFor(tx, "ActiveStorage::VariantRecord", variant.id(), "image");
            tx.update("DELETE FROM \"active_storage_attachments\" WHERE \"record_type\" = 'ActiveStorage::VariantRecord' AND \"record_id\" = ?", variant.id());
            if (variantBlob != null) {
                keys.add(variantBlob.key());
                tx.update("DELETE FROM \"active_storage_blobs\" WHERE \"id\" = ?", variantBlob.id());
            }
        }
        tx.update("DELETE FROM \"active_storage_variant_records\" WHERE \"blob_id\" = ?", id);
        // A preview image (video posters) is attached to the blob itself.
        for (var preview : Attachment.forRecord(tx, "ActiveStorage::Blob", id)) {
            tx.update("DELETE FROM \"active_storage_attachments\" WHERE \"id\" = ?", preview.id());
            keys.addAll(deleteWithVariants(tx, preview.blobId()));
        }
        tx.update("DELETE FROM \"active_storage_blobs\" WHERE \"id\" = ?", id);
        return keys;
    }
}
