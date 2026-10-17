package campfire.storage;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import java.util.List;

/// A row of `active_storage_variant_records`; its processed image is attached to it as `image`.
public record VariantRecord(long id, long blobId, String variationDigest) {
    private static final String SELECT = "SELECT \"id\", \"blob_id\", \"variation_digest\" FROM \"active_storage_variant_records\"";

    public static VariantRecord find(Conn conn, long blobId, String digest) {
        return conn.one(SELECT + " WHERE \"blob_id\" = ? AND \"variation_digest\" = ? LIMIT 1",
            rs -> new VariantRecord(rs.getLong(1), rs.getLong(2), rs.getString(3)), blobId, digest);
    }

    public static List<VariantRecord> forBlob(Conn conn, long blobId) {
        return conn.list(SELECT + " WHERE \"blob_id\" = ?", rs -> new VariantRecord(rs.getLong(1), rs.getLong(2), rs.getString(3)), blobId);
    }

    /// The processed variant's blob, or null when not processed yet.
    public static Blob processedBlob(Conn conn, long blobId, String digest) {
        return conn.one(Blob.SELECT + " INNER JOIN \"active_storage_attachments\" ON \"active_storage_attachments\".\"blob_id\" = \"active_storage_blobs\".\"id\""
                + " INNER JOIN \"active_storage_variant_records\" ON \"active_storage_variant_records\".\"id\" = \"active_storage_attachments\".\"record_id\""
                + " WHERE \"active_storage_attachments\".\"record_type\" = 'ActiveStorage::VariantRecord' AND \"active_storage_attachments\".\"name\" = 'image'"
                + " AND \"active_storage_variant_records\".\"blob_id\" = ? AND \"active_storage_variant_records\".\"variation_digest\" = ? LIMIT 1",
            Blob::from, blobId, digest);
    }

    public static long create(Tx tx, long blobId, String digest) {
        return tx.insert("INSERT INTO \"active_storage_variant_records\" (\"blob_id\", \"variation_digest\") VALUES (?, ?)", blobId, digest);
    }
}
