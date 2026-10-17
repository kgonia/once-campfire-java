package campfire.push;

import campfire.rooms.Membership;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/// A row of `push_subscriptions`: a browser's Web Push endpoint and keys.
public record PushSubscription(long id, long userId, String endpoint, String p256dhKey, String authKey, String userAgent) {
    private static final String COLUMNS = "\"push_subscriptions\".\"id\", \"push_subscriptions\".\"user_id\", \"push_subscriptions\".\"endpoint\", "
        + "\"push_subscriptions\".\"p256dh_key\", \"push_subscriptions\".\"auth_key\", \"push_subscriptions\".\"user_agent\"";

    static PushSubscription from(ResultSet rs) throws SQLException {
        return new PushSubscription(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
    }

    public static List<PushSubscription> forUser(Conn conn, long userId) {
        return conn.list("SELECT " + COLUMNS + " FROM \"push_subscriptions\" WHERE \"user_id\" = ? ORDER BY \"id\"", PushSubscription::from, userId);
    }

    public static PushSubscription find(Conn conn, long userId, long id) {
        return conn.one("SELECT " + COLUMNS + " FROM \"push_subscriptions\" WHERE \"user_id\" = ? AND \"id\" = ? LIMIT 1", PushSubscription::from, userId, id);
    }

    public static PushSubscription findByKeys(Conn conn, long userId, String endpoint, String p256dh, String auth) {
        return conn.one("SELECT " + COLUMNS + " FROM \"push_subscriptions\" WHERE \"user_id\" = ? AND \"endpoint\" IS ? AND \"p256dh_key\" IS ?"
            + " AND \"auth_key\" IS ? LIMIT 1", PushSubscription::from, userId, endpoint, p256dh, auth);
    }

    /// Subscriptions of a room's visible, disconnected members other than the creator, whose
    /// involvement is `everything`, or `mentions` and they're among `mentionedUserIds`.
    public static List<PushSubscription> forRoomDelivery(Conn conn, long roomId, long creatorId, List<Long> mentionedUserIds, Instant now) {
        var args = new java.util.ArrayList<Object>(List.of(roomId, now.minus(Membership.CONNECTION_TTL), creatorId));
        String mentions = "";
        if (!mentionedUserIds.isEmpty()) {
            mentions = " OR (\"memberships\".\"involvement\" = 'mentions' AND \"push_subscriptions\".\"user_id\" IN (" + Conn.placeholders(mentionedUserIds.size()) + "))";
            args.addAll(mentionedUserIds);
        }
        return conn.list("SELECT " + COLUMNS + " FROM \"push_subscriptions\" INNER JOIN \"users\" ON \"users\".\"id\" = \"push_subscriptions\".\"user_id\""
            + " INNER JOIN \"memberships\" ON \"memberships\".\"user_id\" = \"users\".\"id\""
            + " WHERE \"memberships\".\"room_id\" = ? AND \"memberships\".\"involvement\" != 'invisible'"
            + " AND (\"memberships\".\"connected_at\" IS NULL OR \"memberships\".\"connected_at\" < ?) AND \"memberships\".\"user_id\" != ?"
            + " AND (\"memberships\".\"involvement\" = 'everything'" + mentions + ")"
            + " ORDER BY \"push_subscriptions\".\"id\"", PushSubscription::from, args.toArray());
    }

    public static void create(Tx tx, long userId, String endpoint, String p256dh, String auth, String userAgent) {
        Instant now = Times.now();
        tx.insert("INSERT INTO \"push_subscriptions\" (\"auth_key\", \"created_at\", \"endpoint\", \"p256dh_key\", \"updated_at\", \"user_agent\", \"user_id\")"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)", auth, now, endpoint, p256dh, now, userAgent, userId);
    }

    public static void touch(Tx tx, long id) {
        tx.update("UPDATE \"push_subscriptions\" SET \"updated_at\" = ? WHERE \"id\" = ?", Times.now(), id);
    }

    public static void delete(Tx tx, long id) {
        tx.update("DELETE FROM \"push_subscriptions\" WHERE \"id\" = ?", id);
    }

    public static void deleteForUser(Tx tx, long userId, Long id, String endpoint) {
        if (id != null) tx.update("DELETE FROM \"push_subscriptions\" WHERE \"user_id\" = ? AND \"id\" = ?", userId, id);
        if (endpoint != null) tx.update("DELETE FROM \"push_subscriptions\" WHERE \"user_id\" = ? AND \"endpoint\" = ?", userId, endpoint);
    }

    public static void deleteAllFor(Tx tx, long userId) {
        tx.update("DELETE FROM \"push_subscriptions\" WHERE \"user_id\" = ?", userId);
    }
}
