package campfire.rooms;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/// A row of `memberships`. `involvement` is `invisible`, `nothing`, `mentions` or `everything`.
public record Membership(long id, long roomId, long userId, String involvement, Instant unreadAt, Instant connectedAt, int connections,
                         Instant createdAt, Instant updatedAt) {
    /// `Membership::Connectable::CONNECTION_TTL`
    public static final Duration CONNECTION_TTL = Duration.ofSeconds(60);

    public static final String COLUMNS = "\"memberships\".\"id\", \"memberships\".\"room_id\", \"memberships\".\"user_id\", "
        + "\"memberships\".\"involvement\", \"memberships\".\"unread_at\", \"memberships\".\"connected_at\", \"memberships\".\"connections\", "
        + "\"memberships\".\"created_at\", \"memberships\".\"updated_at\"";
    public static final String SELECT = "SELECT " + COLUMNS + " FROM \"memberships\"";
    public static final int COLUMN_COUNT = 9;

    public static Membership from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    public static Membership from(ResultSet rs, int offset) throws SQLException {
        return new Membership(rs.getLong(offset + 1), rs.getLong(offset + 2), rs.getLong(offset + 3), rs.getString(offset + 4),
            Conn.getTime(rs, offset + 5), Conn.getTime(rs, offset + 6), rs.getInt(offset + 7), Conn.getTime(rs, offset + 8),
            Conn.getTime(rs, offset + 9));
    }

    public boolean isUnread() {
        return unreadAt != null;
    }

    public boolean isConnected(Instant now) {
        return connectedAt != null && !connectedAt.isBefore(now.minus(CONNECTION_TTL));
    }

    public String involvementOrDefault() {
        return involvement == null ? "mentions" : involvement;
    }

    /// A membership with its room, as the sidebar and profile list them.
    public record WithRoom(Membership membership, Room room) {}

    // -- Queries -------------------------------------------------------------------------------

    public static Membership find(Conn conn, long roomId, long userId) {
        return conn.one(SELECT + " WHERE \"memberships\".\"room_id\" = ? AND \"memberships\".\"user_id\" = ? LIMIT 1", Membership::from, roomId, userId);
    }

    public static boolean exists(Conn conn, long roomId, long userId) {
        return conn.exists("SELECT 1 FROM \"memberships\" WHERE \"memberships\".\"room_id\" = ? AND \"memberships\".\"user_id\" = ? LIMIT 1", roomId, userId);
    }

    private static final String WITH_ROOM = "SELECT " + COLUMNS + ", " + Room.COLUMNS
        + " FROM \"memberships\" INNER JOIN \"rooms\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\" WHERE \"memberships\".\"user_id\" = ?";

    private static WithRoom withRoom(ResultSet rs) throws SQLException {
        return new WithRoom(from(rs, 0), Room.from(rs, COLUMN_COUNT));
    }

    /// `user.memberships.with_ordered_room`
    public static List<WithRoom> withOrderedRoom(Conn conn, long userId) {
        return conn.list(WITH_ROOM + " ORDER BY LOWER(rooms.name)", Membership::withRoom, userId);
    }

    /// `user.memberships.visible.with_ordered_room`
    public static List<WithRoom> visibleWithOrderedRoom(Conn conn, long userId) {
        return conn.list(WITH_ROOM + " AND \"memberships\".\"involvement\" != 'invisible'"
            + " ORDER BY LOWER(rooms.name)", Membership::withRoom, userId);
    }

    public static List<Membership> forRoom(Conn conn, long roomId) {
        return conn.list(SELECT + " WHERE \"memberships\".\"room_id\" = ? ORDER BY \"memberships\".\"id\"", Membership::from, roomId);
    }

    /// `user.memberships.unread.count`, the push badge.
    public static long unreadCount(Conn conn, long userId) {
        return conn.count("SELECT COUNT(*) FROM \"memberships\" WHERE \"memberships\".\"user_id\" = ? AND \"memberships\".\"unread_at\" IS NOT NULL", userId);
    }

    /// User ids sharing a direct room with `userId` (including `userId`).
    public static List<Long> userIdsInDirectRoomsWith(Conn conn, long userId) {
        List<Long> roomIds = Room.directIdsForUser(conn, userId);
        if (roomIds.isEmpty()) return List.of();
        return conn.longs("SELECT DISTINCT \"memberships\".\"user_id\" FROM \"memberships\" WHERE \"memberships\".\"room_id\" IN ("
            + Conn.placeholders(roomIds.size()) + ")", roomIds.toArray());
    }

    // -- Writes --------------------------------------------------------------------------------

    /// `room.memberships.grant_to(users)`: `insert_all`, skipping existing memberships.
    public static void grantTo(Tx tx, Room room, List<Long> userIds) {
        Instant now = Times.now();
        for (long userId : userIds) {
            tx.update("INSERT INTO \"memberships\" (\"room_id\", \"user_id\", \"involvement\", \"created_at\", \"updated_at\") VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT DO NOTHING", room.id(), userId, room.defaultInvolvement(), now, now);
        }
    }

    /// `room.memberships.revoke_from(users)`; returns the users who lost access.
    public static List<Long> revokeFrom(Tx tx, long roomId, List<Long> userIds) {
        var revoked = new java.util.ArrayList<Long>();
        for (long userId : userIds) {
            if (tx.update("DELETE FROM \"memberships\" WHERE \"memberships\".\"room_id\" = ? AND \"memberships\".\"user_id\" = ?", roomId, userId) > 0) {
                revoked.add(userId);
            }
        }
        return revoked;
    }

    public static void updateInvolvement(Tx tx, long id, String involvement) {
        tx.update("UPDATE \"memberships\" SET \"involvement\" = ?, \"updated_at\" = ? WHERE \"memberships\".\"id\" = ?", involvement, Times.now(), id);
    }

    /// `Room#unread_memberships`: visible, disconnected memberships other than the creator's.
    public static void markUnread(Tx tx, long roomId, long creatorId, Instant messageCreatedAt) {
        Instant now = Times.now();
        tx.update("UPDATE \"memberships\" SET \"unread_at\" = ?, \"updated_at\" = ? WHERE \"memberships\".\"room_id\" = ?"
                + " AND \"memberships\".\"involvement\" != 'invisible'"
                + " AND (\"memberships\".\"connected_at\" IS NULL OR \"memberships\".\"connected_at\" < ?)"
                + " AND \"memberships\".\"user_id\" != ?",
            messageCreatedAt, now, roomId, now.minus(CONNECTION_TTL), creatorId);
    }

    /// `Membership.disconnect_all` at boot.
    public static void disconnectAll(Tx tx) {
        Instant now = Times.now();
        tx.update("UPDATE \"memberships\" SET \"connected_at\" = NULL, \"connections\" = 0, \"updated_at\" = ? WHERE \"memberships\".\"connected_at\" >= ?",
            now, now.minus(CONNECTION_TTL));
    }

    /// `membership.present`: connected, read.
    public static void present(Tx tx, long roomId, long userId) {
        var m = find(tx, roomId, userId);
        if (m == null) return;
        Instant now = Times.now();
        int connections = m.isConnected(now) ? m.connections() + 1 : 1;
        tx.update("UPDATE \"memberships\" SET \"connections\" = ?, \"connected_at\" = ?, \"unread_at\" = NULL WHERE \"memberships\".\"id\" = ?",
            connections, now, m.id());
    }

    /// `membership.disconnected`
    public static void disconnected(Tx tx, long roomId, long userId) {
        var m = find(tx, roomId, userId);
        if (m == null) return;
        Instant now = Times.now();
        int connections = m.isConnected(now) ? m.connections() - 1 : 0;
        if (connections < 1) {
            tx.update("UPDATE \"memberships\" SET \"connections\" = ?, \"connected_at\" = NULL, \"updated_at\" = ? WHERE \"memberships\".\"id\" = ?",
                connections, now, m.id());
        } else {
            tx.update("UPDATE \"memberships\" SET \"connections\" = ?, \"updated_at\" = ? WHERE \"memberships\".\"id\" = ?", connections, now, m.id());
        }
    }

    /// `membership.refresh_connection`
    public static void refreshConnection(Tx tx, long roomId, long userId) {
        var m = find(tx, roomId, userId);
        if (m == null) return;
        Instant now = Times.now();
        int connections = m.isConnected(now) ? m.connections() : 1;
        tx.update("UPDATE \"memberships\" SET \"connections\" = ?, \"connected_at\" = ?, \"updated_at\" = ? WHERE \"memberships\".\"id\" = ?",
            connections, now, now, m.id());
    }
}
