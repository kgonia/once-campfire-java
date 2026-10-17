package campfire.sessions;

import campfire.users.User;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import campfire.rails.SecureRandoms;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/// A row of `sessions`: a signed-in browser, identified by its `session_token` cookie.
public record Session(long id, long userId, String token, String ipAddress, String userAgent, Instant lastActiveAt, Instant createdAt,
                      Instant updatedAt) {
    /// `Session::ACTIVITY_REFRESH_RATE`
    public static final Duration ACTIVITY_REFRESH_RATE = Duration.ofHours(1);

    public static final String COLUMNS = "\"sessions\".\"id\", \"sessions\".\"user_id\", \"sessions\".\"token\", \"sessions\".\"ip_address\", "
        + "\"sessions\".\"user_agent\", \"sessions\".\"last_active_at\", \"sessions\".\"created_at\", \"sessions\".\"updated_at\"";
    public static final int COLUMN_COUNT = 8;

    public static Session from(ResultSet rs, int offset) throws SQLException {
        return new Session(rs.getLong(offset + 1), rs.getLong(offset + 2), rs.getString(offset + 3), rs.getString(offset + 4),
            rs.getString(offset + 5), Conn.getTime(rs, offset + 6), Conn.getTime(rs, offset + 7), Conn.getTime(rs, offset + 8));
    }

    /// A session with its user, as authentication loads them.
    public record WithUser(Session session, User user) {}

    public static WithUser findByToken(Conn conn, String token) {
        return conn.one("SELECT " + COLUMNS + ", " + User.COLUMNS + " FROM \"sessions\" INNER JOIN \"users\" ON \"users\".\"id\" = \"sessions\".\"user_id\""
            + " WHERE \"sessions\".\"token\" = ? LIMIT 1", rs -> new WithUser(from(rs, 0), User.from(rs, COLUMN_COUNT)), token);
    }

    public static List<String> ipAddressesFor(Conn conn, long userId) {
        return conn.list("SELECT DISTINCT \"ip_address\" FROM \"sessions\" WHERE \"user_id\" = ? AND \"ip_address\" IS NOT NULL AND \"ip_address\" != ''",
            rs -> rs.getString(1), userId);
    }

    /// `user.sessions.start!(user_agent:, ip_address:)`
    public static Session start(Tx tx, long userId, String userAgent, String ipAddress) {
        Instant now = Times.now();
        String token = SecureRandoms.base58(24); // has_secure_token
        long id = tx.insert("INSERT INTO \"sessions\" (\"created_at\", \"ip_address\", \"last_active_at\", \"token\", \"updated_at\", \"user_agent\", \"user_id\")"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)", now, ipAddress, now, token, now, userAgent, userId);
        return new Session(id, userId, token, ipAddress, userAgent, now, now, now);
    }

    /// `session.resume`: true when the hourly activity refresh is due.
    public boolean needsResume(Instant now) {
        return lastActiveAt == null || lastActiveAt.isBefore(now.minus(ACTIVITY_REFRESH_RATE));
    }

    public static void resume(Tx tx, long id, String userAgent, String ipAddress) {
        Instant now = Times.now();
        tx.update("UPDATE \"sessions\" SET \"user_agent\" = ?, \"ip_address\" = ?, \"last_active_at\" = ?, \"updated_at\" = ? WHERE \"sessions\".\"id\" = ?",
            userAgent, ipAddress, now, now, id);
    }

    public static void delete(Tx tx, long id) {
        tx.update("DELETE FROM \"sessions\" WHERE \"sessions\".\"id\" = ?", id);
    }

    public static void deleteAllFor(Tx tx, long userId) {
        tx.update("DELETE FROM \"sessions\" WHERE \"sessions\".\"user_id\" = ?", userId);
    }
}
