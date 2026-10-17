package campfire.messages;

import campfire.richtext.RichTextRecord;
import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.storage.Attachment;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/// A row of `messages`. Its body is an Action Text record and its attachment an Active Storage one.
public record Message(long id, long roomId, long creatorId, String clientMessageId, Instant createdAt, Instant updatedAt) {
    public static final int PAGE_SIZE = 40;
    public static final String RECORD_TYPE = "Message";

    public static final String COLUMNS = "\"messages\".\"id\", \"messages\".\"room_id\", \"messages\".\"creator_id\", "
        + "\"messages\".\"client_message_id\", \"messages\".\"created_at\", \"messages\".\"updated_at\"";
    public static final String SELECT = "SELECT " + COLUMNS + " FROM \"messages\"";
    public static final int COLUMN_COUNT = 6;

    public static Message from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    public static Message from(ResultSet rs, int offset) throws SQLException {
        return new Message(rs.getLong(offset + 1), rs.getLong(offset + 2), rs.getLong(offset + 3), rs.getString(offset + 4),
            Conn.getTime(rs, offset + 5), Conn.getTime(rs, offset + 6));
    }

    /// `to_key`: messages are identified in the DOM by their client id (`message_<client id>`).
    public String domId() {
        return "message_" + clientMessageId;
    }

    public String domId(String prefix) {
        return prefix + "_message_" + clientMessageId;
    }

    // -- Queries -------------------------------------------------------------------------------

    private static final String IN_ROOM = SELECT + " WHERE \"messages\".\"room_id\" = ?";
    private static final String REACHABLE = SELECT + " INNER JOIN \"rooms\" ON \"messages\".\"room_id\" = \"rooms\".\"id\""
        + " INNER JOIN \"memberships\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\"";

    public static Message find(Conn conn, long id) {
        return conn.one(SELECT + " WHERE \"messages\".\"id\" = ? LIMIT 1", Message::from, id);
    }

    public static Message findInRoom(Conn conn, long roomId, long id) {
        return conn.one(IN_ROOM + " AND \"messages\".\"id\" = ? LIMIT 1", Message::from, roomId, id);
    }

    /// `user.reachable_messages.find(id)`
    public static Message findReachable(Conn conn, long userId, long id) {
        return conn.one(REACHABLE + " WHERE \"memberships\".\"user_id\" = ? AND \"messages\".\"id\" = ? LIMIT 1", Message::from, userId, id);
    }

    public static List<Message> forRoom(Conn conn, long roomId) {
        return conn.list(IN_ROOM, Message::from, roomId);
    }

    public static List<Message> byCreator(Conn conn, long creatorId) {
        return conn.list(SELECT + " WHERE \"messages\".\"creator_id\" = ?", Message::from, creatorId);
    }

    public static long countInRoom(Conn conn, long roomId) {
        return conn.count("SELECT COUNT(*) FROM \"messages\" WHERE \"messages\".\"room_id\" = ?", roomId);
    }

    /// `messages.paged?`: more than a page.
    public static boolean paged(Conn conn, long roomId) {
        return conn.exists("SELECT 1 FROM \"messages\" WHERE \"messages\".\"room_id\" = ? LIMIT 1 OFFSET " + PAGE_SIZE, roomId);
    }

    /// `last_page`: the newest page, oldest first.
    public static List<Message> lastPage(Conn conn, long roomId) {
        return reversed(conn.list(IN_ROOM + " ORDER BY \"messages\".\"created_at\" DESC LIMIT " + PAGE_SIZE, Message::from, roomId));
    }

    public static List<Message> pageBefore(Conn conn, long roomId, Message message) {
        return reversed(conn.list(IN_ROOM + " AND (created_at < ?) ORDER BY \"messages\".\"created_at\" DESC LIMIT " + PAGE_SIZE,
            Message::from, roomId, message.createdAt()));
    }

    public static List<Message> pageAfter(Conn conn, long roomId, Message message) {
        return conn.list(IN_ROOM + " AND (created_at > ?) ORDER BY \"messages\".\"created_at\" ASC LIMIT " + PAGE_SIZE,
            Message::from, roomId, message.createdAt());
    }

    public static List<Message> pageAround(Conn conn, long roomId, Message message) {
        var page = new ArrayList<>(pageBefore(conn, roomId, message));
        page.add(message);
        page.addAll(pageAfter(conn, roomId, message));
        return page;
    }

    public static List<Message> pageCreatedSince(Conn conn, long roomId, Instant time) {
        return conn.list(IN_ROOM + " AND (created_at > ?) ORDER BY \"messages\".\"created_at\" ASC LIMIT " + PAGE_SIZE,
            Message::from, roomId, time);
    }

    public static List<Message> pageUpdatedSince(Conn conn, long roomId, Instant time, List<Long> excluding) {
        var args = new ArrayList<Object>();
        args.add(roomId);
        String without = "";
        if (!excluding.isEmpty()) {
            without = " AND \"messages\".\"id\" NOT IN (" + Conn.placeholders(excluding.size()) + ")";
            args.addAll(excluding);
        }
        args.add(time);
        return reversed(conn.list(IN_ROOM + without + " AND (updated_at > ?) ORDER BY +\"messages\".\"created_at\" DESC LIMIT " + PAGE_SIZE,
            Message::from, args.toArray()));
    }

    public static boolean existsBefore(Conn conn, long roomId, Message message) {
        return conn.exists("SELECT 1 FROM \"messages\" WHERE \"messages\".\"room_id\" = ? AND (created_at < ?) LIMIT 1", roomId, message.createdAt());
    }

    public static boolean existsAfter(Conn conn, long roomId, Message message) {
        return conn.exists("SELECT 1 FROM \"messages\" WHERE \"messages\".\"room_id\" = ? AND (created_at > ?) LIMIT 1", roomId, message.createdAt());
    }

    private static final int SEARCH_PROBE_LIMIT = 1000;
    private static final String SEARCH_PROBE = "SELECT " + COLUMNS + ", memberships.user_id IS NOT NULL AS reachable FROM messages"
        + " INNER JOIN rooms ON messages.room_id = rooms.id LEFT JOIN memberships ON rooms.id = memberships.room_id AND memberships.user_id = ?"
        + " JOIN message_search_index idx ON messages.id = idx.rowid WHERE idx.body MATCH ? ORDER BY idx.rowid DESC LIMIT " + SEARCH_PROBE_LIMIT;

    /// `user.reachable_messages.search(query).last(100)`: the newest hundred matches by id. Words are
    /// literal FTS terms. A bounded scan of the newest matches answers most searches; when most of
    /// them are in rooms the user can't see, a membership-scoped query does.
    public static List<Message> searchReachable(Conn conn, long userId, String query) {
        String terms = matchTerms(query);
        if (terms.isEmpty()) return List.of();
        var found = new ArrayList<Message>();
        var rows = conn.list(SEARCH_PROBE, rs -> rs.getBoolean(COLUMN_COUNT + 1) ? from(rs) : null, userId, terms);
        int examined = rows.size();
        for (var message : rows) {
            if (message == null) continue;
            found.add(message);
            if (found.size() == 100) return reversed(found);
        }
        if (examined < SEARCH_PROBE_LIMIT) return reversed(found);
        return reversed(conn.list(REACHABLE + " join message_search_index idx on messages.id = idx.rowid WHERE \"memberships\".\"user_id\" = ?"
            + " AND (idx.body match ?) ORDER BY \"messages\".\"id\" DESC LIMIT 100", Message::from, userId, terms));
    }

    /// Each whitespace-separated word as a quoted FTS5 string, so `NOT`, `OR` and `*` are literal.
    static String matchTerms(String query) {
        var out = new StringBuilder();
        for (String word : query.split("[\\s\\x00]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append('"').append(word.replace("\"", "\"\"")).append('"');
        }
        return out.toString();
    }

    private static <T> List<T> reversed(List<T> list) {
        Collections.reverse(list);
        return list;
    }

    // -- Writes --------------------------------------------------------------------------------

    /// Creates the message with its body or attachment, indexes it for search and marks the room
    /// unread for everyone else, in one transaction (as current Rails does).
    public static Message create(Tx tx, long roomId, long creatorId, String clientMessageId, String bodyHtml, Long attachmentBlobId,
                                 String plainText) {
        Instant now = Times.now();
        String clientId = clientMessageId == null || clientMessageId.isBlank() ? UUID.randomUUID().toString() : clientMessageId;
        long id = tx.insert("INSERT INTO \"messages\" (\"client_message_id\", \"created_at\", \"creator_id\", \"room_id\", \"updated_at\")"
            + " VALUES (?, ?, ?, ?, ?)", clientId, now, creatorId, roomId, now);
        // Each record gets its own clock reading, and saving the body or attachment touches the
        // message, so `updated_at` comes out just after `created_at`, as in Rails.
        Instant updatedAt = now;
        if (bodyHtml != null) RichTextRecord.create(tx, RECORD_TYPE, id, "body", bodyHtml, Times.now());
        if (attachmentBlobId != null) Attachment.create(tx, RECORD_TYPE, id, "attachment", attachmentBlobId, Times.now());
        if (bodyHtml != null || attachmentBlobId != null) {
            updatedAt = Times.now();
            tx.update("UPDATE \"messages\" SET \"updated_at\" = ? WHERE \"messages\".\"id\" = ?", updatedAt, id);
        }
        Room.touch(tx, roomId);
        tx.update("insert into message_search_index(rowid, body) values (?, ?)", id, plainText);
        Membership.markUnread(tx, roomId, creatorId, now);
        return new Message(id, roomId, creatorId, clientId, now, updatedAt);
    }

    /// Replaces the body (if it changed), touches the message and room and reindexes it.
    public static Message updateBody(Tx tx, Message message, String bodyHtml, String plainText) {
        var record = RichTextRecord.find(tx, RECORD_TYPE, message.id(), "body");
        if (record != null && bodyHtml.equals(record.body())) return message;
        Instant now = Times.now();
        if (record == null) RichTextRecord.create(tx, RECORD_TYPE, message.id(), "body", bodyHtml, now);
        else RichTextRecord.updateBody(tx, record.id(), bodyHtml, now);
        return touch(tx, message, plainText);
    }

    /// `message.touch` (a boost or body change), reindexing with `plainText` when given.
    public static Message touch(Tx tx, Message message, String plainText) {
        Instant now = Times.now();
        tx.update("UPDATE \"messages\" SET \"updated_at\" = ? WHERE \"messages\".\"id\" = ?", now, message.id());
        Room.touch(tx, message.roomId());
        if (plainText != null) tx.update("update message_search_index set body = ? where rowid = ?", plainText, message.id());
        return new Message(message.id(), message.roomId(), message.creatorId(), message.clientMessageId(), message.createdAt(), now);
    }

    /// `message.destroy`: boosts, body, attachment and index entry go with it. Returns the blob to purge.
    public static Long destroy(Tx tx, Message message, boolean touchRoom) {
        Long blobId = null;
        var attachment = Attachment.find(tx, RECORD_TYPE, message.id(), "attachment");
        if (attachment != null) {
            Attachment.delete(tx, attachment.id());
            blobId = attachment.blobId();
        }
        tx.update("DELETE FROM \"boosts\" WHERE \"boosts\".\"message_id\" = ?", message.id());
        tx.update("DELETE FROM \"action_text_rich_texts\" WHERE \"record_type\" = 'Message' AND \"record_id\" = ?", message.id());
        tx.update("DELETE FROM \"messages\" WHERE \"messages\".\"id\" = ?", message.id());
        tx.update("delete from message_search_index where rowid = ?", message.id());
        if (touchRoom) Room.touch(tx, message.roomId());
        return blobId;
    }
}
