package campfire.storage;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// A row of `active_storage_attachments`: `record` has one attached `name`, the blob.
public record Attachment(long id, String name, String recordType, long recordId, long blobId) {
    private static final String SELECT = "SELECT \"id\", \"name\", \"record_type\", \"record_id\", \"blob_id\" FROM \"active_storage_attachments\"";

    private static Attachment row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Attachment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5));
    }

    public static Attachment find(Conn conn, String recordType, long recordId, String name) {
        return conn.one(SELECT + " WHERE \"record_type\" = ? AND \"record_id\" = ? AND \"name\" = ? ORDER BY \"id\" LIMIT 1", Attachment::row,
            recordType, recordId, name);
    }

    public static List<Attachment> forRecord(Conn conn, String recordType, long recordId) {
        return conn.list(SELECT + " WHERE \"record_type\" = ? AND \"record_id\" = ? ORDER BY \"id\"", Attachment::row, recordType, recordId);
    }

    public static Blob findBlobFor(Conn conn, String recordType, long recordId, String name) {
        return conn.one(Blob.SELECT + " INNER JOIN \"active_storage_attachments\" ON \"active_storage_attachments\".\"blob_id\" = \"active_storage_blobs\".\"id\""
                + " WHERE \"active_storage_attachments\".\"record_type\" = ? AND \"active_storage_attachments\".\"record_id\" = ?"
                + " AND \"active_storage_attachments\".\"name\" = ? ORDER BY \"active_storage_attachments\".\"id\" LIMIT 1",
            Blob::from, recordType, recordId, name);
    }

    /// Attached blobs by record id, for a page of records.
    public static Map<Long, Blob> blobsFor(Conn conn, String recordType, String name, List<Long> recordIds) {
        var blobs = new HashMap<Long, Blob>();
        if (recordIds.isEmpty()) return blobs;
        var args = new java.util.ArrayList<Object>();
        args.add(recordType);
        args.add(name);
        args.addAll(recordIds);
        conn.list("SELECT \"active_storage_attachments\".\"record_id\", " + Blob.COLUMNS + " FROM \"active_storage_blobs\""
            + " INNER JOIN \"active_storage_attachments\" ON \"active_storage_attachments\".\"blob_id\" = \"active_storage_blobs\".\"id\""
            + " WHERE \"active_storage_attachments\".\"record_type\" = ? AND \"active_storage_attachments\".\"name\" = ?"
            + " AND \"active_storage_attachments\".\"record_id\" IN (" + Conn.placeholders(recordIds.size()) + ")", rs -> {
            blobs.putIfAbsent(rs.getLong(1), Blob.from(rs, 1));
            return null;
        }, args.toArray());
        return blobs;
    }

    public static void create(Tx tx, String recordType, long recordId, String name, long blobId, Instant now) {
        tx.insert("INSERT INTO \"active_storage_attachments\" (\"blob_id\", \"created_at\", \"name\", \"record_id\", \"record_type\") VALUES (?, ?, ?, ?, ?)",
            blobId, now, name, recordId, recordType);
    }

    public static void delete(Tx tx, long id) {
        tx.update("DELETE FROM \"active_storage_attachments\" WHERE \"id\" = ?", id);
    }

    /// Detaches `record`'s `name` attachment; returns the blob id to purge, or null.
    public static Long detach(Tx tx, String recordType, long recordId, String name) {
        var existing = find(tx, recordType, recordId, name);
        if (existing == null) return null;
        delete(tx, existing.id());
        return existing.blobId();
    }

    public static boolean blobAttachedElsewhere(Conn conn, long blobId) {
        return conn.exists("SELECT 1 FROM \"active_storage_attachments\" WHERE \"blob_id\" = ? LIMIT 1", blobId);
    }
}
