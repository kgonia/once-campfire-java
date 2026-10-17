package campfire.storage;

import static campfire.db.schema.Tables.ACTIVE_STORAGE_ATTACHMENTS;
import static campfire.db.schema.Tables.ACTIVE_STORAGE_BLOBS;
import static campfire.db.schema.Tables.ACTIVE_STORAGE_VARIANT_RECORDS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import java.util.List;
import org.jooq.Field;

/// A row of `active_storage_variant_records`; its processed image is attached to it as `image`.
public record VariantRecord(long id, long blobId, String variationDigest) {
    private static final Field<?>[] COLUMNS = {ACTIVE_STORAGE_VARIANT_RECORDS.ID, ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID,
        ACTIVE_STORAGE_VARIANT_RECORDS.VARIATION_DIGEST};

    private static VariantRecord row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VariantRecord(rs.getLong(1), rs.getLong(2), rs.getString(3));
    }

    private static final String FIND = Sql.render(select(COLUMNS).from(ACTIVE_STORAGE_VARIANT_RECORDS)
        .where(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID.eq(param(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID)))
        .and(ACTIVE_STORAGE_VARIANT_RECORDS.VARIATION_DIGEST.eq(param(ACTIVE_STORAGE_VARIANT_RECORDS.VARIATION_DIGEST))).limit(inline(1)));
    private static final String FOR_BLOB = Sql.render(select(COLUMNS).from(ACTIVE_STORAGE_VARIANT_RECORDS)
        .where(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID.eq(param(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID))));
    private static final String PROCESSED_BLOB = Sql.render(select(Blob.COLUMNS).from(ACTIVE_STORAGE_BLOBS)
        .join(ACTIVE_STORAGE_ATTACHMENTS).on(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID.eq(ACTIVE_STORAGE_BLOBS.ID))
        .join(ACTIVE_STORAGE_VARIANT_RECORDS).on(ACTIVE_STORAGE_VARIANT_RECORDS.ID.eq(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID))
        .where(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE.eq(inline("ActiveStorage::VariantRecord")))
        .and(ACTIVE_STORAGE_ATTACHMENTS.NAME.eq(inline("image")))
        .and(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID.eq(param(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID)))
        .and(ACTIVE_STORAGE_VARIANT_RECORDS.VARIATION_DIGEST.eq(param(ACTIVE_STORAGE_VARIANT_RECORDS.VARIATION_DIGEST))).limit(inline(1)));
    private static final String INSERT = Sql.render(insertInto(ACTIVE_STORAGE_VARIANT_RECORDS, ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID,
            ACTIVE_STORAGE_VARIANT_RECORDS.VARIATION_DIGEST)
        .values(param(ACTIVE_STORAGE_VARIANT_RECORDS.BLOB_ID), param(ACTIVE_STORAGE_VARIANT_RECORDS.VARIATION_DIGEST)));
    private static final String DELETE = Sql.render(deleteFrom(ACTIVE_STORAGE_VARIANT_RECORDS)
        .where(ACTIVE_STORAGE_VARIANT_RECORDS.ID.eq(param(ACTIVE_STORAGE_VARIANT_RECORDS.ID))));

    public static VariantRecord find(Conn conn, long blobId, String digest) {
        return conn.one(FIND, VariantRecord::row, blobId, digest);
    }

    public static List<VariantRecord> forBlob(Conn conn, long blobId) {
        return conn.list(FOR_BLOB, VariantRecord::row, blobId);
    }

    /// The processed variant's blob, or null when not processed yet.
    public static Blob processedBlob(Conn conn, long blobId, String digest) {
        return conn.one(PROCESSED_BLOB, Blob::from, blobId, digest);
    }

    public static long create(Tx tx, long blobId, String digest) {
        return tx.insert(INSERT, blobId, digest);
    }

    public static void delete(Tx tx, long id) {
        tx.update(DELETE, id);
    }
}
