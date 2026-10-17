package campfire.richtext;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// A row of `action_text_rich_texts`.
public record RichTextRecord(long id, String recordType, long recordId, String name, String body) {
    private static final String SELECT = "SELECT \"id\", \"record_type\", \"record_id\", \"name\", \"body\" FROM \"action_text_rich_texts\"";

    public static RichTextRecord find(Conn conn, String recordType, long recordId, String name) {
        return conn.one(SELECT + " WHERE \"record_type\" = ? AND \"record_id\" = ? AND \"name\" = ? LIMIT 1",
            rs -> new RichTextRecord(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)), recordType, recordId, name);
    }

    /// Message bodies by message id.
    public static Map<Long, String> messageBodies(Conn conn, List<Long> messageIds) {
        var bodies = new HashMap<Long, String>();
        if (messageIds.isEmpty()) return bodies;
        conn.list("SELECT \"record_id\", \"body\" FROM \"action_text_rich_texts\" WHERE \"record_type\" = 'Message' AND \"name\" = 'body'"
            + " AND \"record_id\" IN (" + Conn.placeholders(messageIds.size()) + ")", rs -> {
            bodies.put(rs.getLong(1), rs.getString(2));
            return null;
        }, messageIds.toArray());
        return bodies;
    }

    public static void create(Tx tx, String recordType, long recordId, String name, String body, Instant now) {
        tx.insert("INSERT INTO \"action_text_rich_texts\" (\"body\", \"created_at\", \"name\", \"record_id\", \"record_type\", \"updated_at\")"
            + " VALUES (?, ?, ?, ?, ?, ?)", body, now, name, recordId, recordType, now);
    }

    public static void updateBody(Tx tx, long id, String body, Instant now) {
        tx.update("UPDATE \"action_text_rich_texts\" SET \"body\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", body, now, id);
    }
}
