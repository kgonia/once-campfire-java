package campfire.messages;

import campfire.users.User;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// A row of `boosts`: a reaction of at most 16 characters.
public record Boost(long id, long messageId, long boosterId, String content, Instant createdAt, Instant updatedAt) {
    public static final String COLUMNS = "\"boosts\".\"id\", \"boosts\".\"message_id\", \"boosts\".\"booster_id\", \"boosts\".\"content\", "
        + "\"boosts\".\"created_at\", \"boosts\".\"updated_at\"";
    public static final int COLUMN_COUNT = 6;

    public static Boost from(ResultSet rs, int offset) throws SQLException {
        return new Boost(rs.getLong(offset + 1), rs.getLong(offset + 2), rs.getLong(offset + 3), rs.getString(offset + 4),
            Conn.getTime(rs, offset + 5), Conn.getTime(rs, offset + 6));
    }

    public String domId() {
        return "boost_" + id;
    }

    /// A boost with its booster.
    public record WithBooster(Boost boost, User booster) {}

    public static Boost find(Conn conn, long messageId, long id) {
        return conn.one("SELECT " + COLUMNS + " FROM \"boosts\" WHERE \"boosts\".\"message_id\" = ? AND \"boosts\".\"id\" = ? LIMIT 1",
            rs -> from(rs, 0), messageId, id);
    }

    public static List<WithBooster> forMessage(Conn conn, long messageId) {
        return forMessages(conn, List.of(messageId)).getOrDefault(messageId, List.of());
    }

    /// `message.boosts.ordered` with boosters, for a page of messages.
    public static Map<Long, List<WithBooster>> forMessages(Conn conn, List<Long> messageIds) {
        var out = new LinkedHashMap<Long, List<WithBooster>>();
        if (messageIds.isEmpty()) return out;
        conn.list("SELECT " + COLUMNS + ", " + User.COLUMNS + " FROM \"boosts\" INNER JOIN \"users\" ON \"users\".\"id\" = \"boosts\".\"booster_id\""
            + " WHERE \"boosts\".\"message_id\" IN (" + Conn.placeholders(messageIds.size()) + ") ORDER BY \"boosts\".\"created_at\" ASC", rs -> {
            var boost = from(rs, 0);
            out.computeIfAbsent(boost.messageId(), k -> new ArrayList<>()).add(new WithBooster(boost, User.from(rs, COLUMN_COUNT)));
            return null;
        }, messageIds.toArray());
        return out;
    }

    public static Boost create(Tx tx, long messageId, long boosterId, String content) {
        Instant now = Times.now();
        long id = tx.insert("INSERT INTO \"boosts\" (\"booster_id\", \"content\", \"created_at\", \"message_id\", \"updated_at\") VALUES (?, ?, ?, ?, ?)",
            boosterId, content, now, messageId, now);
        return new Boost(id, messageId, boosterId, content, now, now);
    }

    public static void delete(Tx tx, long id) {
        tx.update("DELETE FROM \"boosts\" WHERE \"boosts\".\"id\" = ?", id);
    }
}
