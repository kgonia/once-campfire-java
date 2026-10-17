package campfire.search;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.time.Instant;
import java.util.List;

/// A row of `searches`: a user's recent search queries (the ten newest are kept).
public record Search(long id, long userId, String query, Instant updatedAt) {
    public static List<Search> recentFor(Conn conn, long userId) {
        return conn.list("SELECT \"id\", \"user_id\", \"query\", \"updated_at\" FROM \"searches\" WHERE \"user_id\" = ? ORDER BY \"updated_at\" DESC",
            rs -> new Search(rs.getLong(1), rs.getLong(2), rs.getString(3), Conn.getTime(rs, 4)), userId);
    }

    /// `user.searches.record(query)`: find or create, touch, keep the ten newest.
    public static void record(Tx tx, long userId, String query) {
        Instant now = Times.now();
        Long id = tx.longValue("SELECT \"id\" FROM \"searches\" WHERE \"user_id\" = ? AND \"query\" = ? LIMIT 1", userId, query);
        if (id == null) {
            tx.insert("INSERT INTO \"searches\" (\"created_at\", \"query\", \"updated_at\", \"user_id\") VALUES (?, ?, ?, ?)", now, query, now, userId);
            tx.update("DELETE FROM \"searches\" WHERE \"user_id\" = ? AND \"id\" NOT IN"
                + " (SELECT \"id\" FROM \"searches\" WHERE \"user_id\" = ? ORDER BY \"updated_at\" DESC LIMIT 10)", userId, userId);
        } else {
            tx.update("UPDATE \"searches\" SET \"updated_at\" = ? WHERE \"id\" = ?", now, id);
        }
    }

    public static void clear(Tx tx, long userId) {
        tx.update("DELETE FROM \"searches\" WHERE \"user_id\" = ?", userId);
    }
}
