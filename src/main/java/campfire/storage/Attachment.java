package campfire.storage;

import static campfire.db.schema.Tables.ACTIVE_STORAGE_ATTACHMENTS;
import static campfire.db.schema.Tables.ACTIVE_STORAGE_BLOBS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.jooq.Field;

/// A row of `active_storage_attachments`: `record` has one attached `name`, the blob.
public record Attachment(long id, String name, String recordType, long recordId, long blobId) {
    private static final Field<?>[] COLUMNS = {ACTIVE_STORAGE_ATTACHMENTS.ID, ACTIVE_STORAGE_ATTACHMENTS.NAME, ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE,
        ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID, ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID};

    private static Attachment row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Attachment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5));
    }

    private static final String FIND = Sql.render(select(COLUMNS).from(ACTIVE_STORAGE_ATTACHMENTS)
        .where(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE)))
        .and(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID)))
        .and(ACTIVE_STORAGE_ATTACHMENTS.NAME.eq(param(ACTIVE_STORAGE_ATTACHMENTS.NAME)))
        .orderBy(ACTIVE_STORAGE_ATTACHMENTS.ID).limit(inline(1)));
    private static final String FOR_RECORD = Sql.render(select(COLUMNS).from(ACTIVE_STORAGE_ATTACHMENTS)
        .where(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE)))
        .and(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID)))
        .orderBy(ACTIVE_STORAGE_ATTACHMENTS.ID));
    private static final String FOR_BLOB = Sql.render(select(COLUMNS).from(ACTIVE_STORAGE_ATTACHMENTS)
        .where(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID.eq(param(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID))).orderBy(ACTIVE_STORAGE_ATTACHMENTS.ID));
    private static final String FIND_BLOB_FOR = Sql.render(select(Blob.COLUMNS).from(ACTIVE_STORAGE_BLOBS)
        .join(ACTIVE_STORAGE_ATTACHMENTS).on(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID.eq(ACTIVE_STORAGE_BLOBS.ID))
        .where(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE)))
        .and(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID)))
        .and(ACTIVE_STORAGE_ATTACHMENTS.NAME.eq(param(ACTIVE_STORAGE_ATTACHMENTS.NAME)))
        .orderBy(ACTIVE_STORAGE_ATTACHMENTS.ID).limit(inline(1)));
    /// Binds record type, name, then the record ids.
    private static final IntFunction<String> BLOBS_FOR = Sql.perCount(n -> select(Stream.concat(Stream.of(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID),
            Stream.of(Blob.COLUMNS)).toList())
        .from(ACTIVE_STORAGE_BLOBS).join(ACTIVE_STORAGE_ATTACHMENTS).on(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID.eq(ACTIVE_STORAGE_BLOBS.ID))
        .where(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE.eq(param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE)))
        .and(ACTIVE_STORAGE_ATTACHMENTS.NAME.eq(param(ACTIVE_STORAGE_ATTACHMENTS.NAME)))
        .and(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID.in(Sql.params(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID, n))));
    private static final String INSERT = Sql.render(insertInto(ACTIVE_STORAGE_ATTACHMENTS, ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID,
            ACTIVE_STORAGE_ATTACHMENTS.CREATED_AT, ACTIVE_STORAGE_ATTACHMENTS.NAME, ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID, ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE)
        .values(param(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID), param(ACTIVE_STORAGE_ATTACHMENTS.CREATED_AT), param(ACTIVE_STORAGE_ATTACHMENTS.NAME),
            param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_ID), param(ACTIVE_STORAGE_ATTACHMENTS.RECORD_TYPE)));
    private static final String DELETE = Sql.render(deleteFrom(ACTIVE_STORAGE_ATTACHMENTS)
        .where(ACTIVE_STORAGE_ATTACHMENTS.ID.eq(param(ACTIVE_STORAGE_ATTACHMENTS.ID))));
    private static final String ATTACHED = Sql.render(selectOne().from(ACTIVE_STORAGE_ATTACHMENTS)
        .where(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID.eq(param(ACTIVE_STORAGE_ATTACHMENTS.BLOB_ID))).limit(inline(1)));

    public static Attachment find(Conn conn, String recordType, long recordId, String name) {
        return conn.one(FIND, Attachment::row, recordType, recordId, name);
    }

    public static List<Attachment> forRecord(Conn conn, String recordType, long recordId) {
        return conn.list(FOR_RECORD, Attachment::row, recordType, recordId);
    }

    /// Every attachment of the blob, whatever its record.
    public static List<Attachment> forBlob(Conn conn, long blobId) {
        return conn.list(FOR_BLOB, Attachment::row, blobId);
    }

    public static Blob findBlobFor(Conn conn, String recordType, long recordId, String name) {
        return conn.one(FIND_BLOB_FOR, Blob::from, recordType, recordId, name);
    }

    /// Attached blobs by record id, for a page of records.
    public static Map<Long, Blob> blobsFor(Conn conn, String recordType, String name, List<Long> recordIds) {
        var blobs = new HashMap<Long, Blob>();
        if (recordIds.isEmpty()) return blobs;
        var args = new java.util.ArrayList<Object>();
        args.add(recordType);
        args.add(name);
        args.addAll(recordIds);
        conn.list(BLOBS_FOR.apply(recordIds.size()), rs -> {
            blobs.putIfAbsent(rs.getLong(1), Blob.from(rs, 1));
            return null;
        }, args.toArray());
        return blobs;
    }

    public static void create(Tx tx, String recordType, long recordId, String name, long blobId, Instant now) {
        tx.insert(INSERT, blobId, now, name, recordId, recordType);
    }

    public static void delete(Tx tx, long id) {
        tx.update(DELETE, id);
    }

    /// Detaches `record`'s `name` attachment; returns the blob id to purge, or null.
    public static Long detach(Tx tx, String recordType, long recordId, String name) {
        var existing = find(tx, recordType, recordId, name);
        if (existing == null) return null;
        delete(tx, existing.id());
        return existing.blobId();
    }

    public static boolean blobAttachedElsewhere(Conn conn, long blobId) {
        return conn.exists(ATTACHED, blobId);
    }
}
