package campfire.bots;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.time.Instant;

/// A row of `webhooks`: where a bot is told about messages that concern it.
public record Webhook(long id, long userId, String url) {
    public static Webhook forUser(Conn conn, long userId) {
        return conn.one("SELECT \"id\", \"user_id\", \"url\" FROM \"webhooks\" WHERE \"user_id\" = ? ORDER BY \"id\" LIMIT 1",
            rs -> new Webhook(rs.getLong(1), rs.getLong(2), rs.getString(3)), userId);
    }

    /// Sets, replaces or (for a blank url) removes a bot's webhook; the same url writes nothing.
    public static void set(Tx tx, long userId, String url) {
        var existing = forUser(tx, userId);
        if (url == null || url.isBlank()) {
            if (existing != null) tx.update("DELETE FROM \"webhooks\" WHERE \"id\" = ?", existing.id());
        } else if (existing == null) {
            create(tx, userId, url);
        } else if (!url.equals(existing.url())) {
            tx.update("UPDATE \"webhooks\" SET \"updated_at\" = ?, \"url\" = ? WHERE \"webhooks\".\"id\" = ?", Times.now(), url, existing.id());
        }
    }

    /// Any url is kept, "" included.
    public static void create(Tx tx, long userId, String url) {
        Instant now = Times.now();
        tx.insert("INSERT INTO \"webhooks\" (\"created_at\", \"updated_at\", \"url\", \"user_id\") VALUES (?, ?, ?, ?)", now, now, url, userId);
    }
}
