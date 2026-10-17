package campfire.rooms;

import campfire.messages.Message;
import campfire.users.User;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/// A row of `rooms`; `type` is `Rooms::Open`, `Rooms::Closed` or `Rooms::Direct` (STI).
public record Room(long id, String name, String type, long creatorId, Instant createdAt, Instant updatedAt) {
    public static final String OPEN = "Rooms::Open", CLOSED = "Rooms::Closed", DIRECT = "Rooms::Direct";

    public static final String COLUMNS = "\"rooms\".\"id\", \"rooms\".\"name\", \"rooms\".\"type\", \"rooms\".\"creator_id\", "
        + "\"rooms\".\"created_at\", \"rooms\".\"updated_at\"";
    public static final String SELECT = "SELECT " + COLUMNS + " FROM \"rooms\"";
    public static final int COLUMN_COUNT = 6;

    public static Room from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    public static Room from(ResultSet rs, int offset) throws SQLException {
        return new Room(rs.getLong(offset + 1), rs.getString(offset + 2), rs.getString(offset + 3), rs.getLong(offset + 4),
            Conn.getTime(rs, offset + 5), Conn.getTime(rs, offset + 6));
    }

    public boolean isOpen() {
        return OPEN.equals(type);
    }

    public boolean isClosed() {
        return CLOSED.equals(type);
    }

    public boolean isDirect() {
        return DIRECT.equals(type);
    }

    public String defaultInvolvement() {
        return isDirect() ? "everything" : "mentions";
    }

    /// The GlobalID model name: the STI class.
    public String modelName() {
        return type;
    }

    public Room withType(String newType) {
        return new Room(id, name, newType, creatorId, createdAt, updatedAt);
    }

    // -- Queries -------------------------------------------------------------------------------

    public static Room find(Conn conn, long id) {
        return conn.one(SELECT + " WHERE \"rooms\".\"id\" = ? LIMIT 1", Room::from, id);
    }

    /// `Room.original`: the first room created.
    public static Room original(Conn conn) {
        return conn.one(SELECT + " ORDER BY \"rooms\".\"created_at\" ASC LIMIT 1", Room::from);
    }

    public static Long originalId(Conn conn) {
        return conn.longValue("SELECT \"rooms\".\"id\" FROM \"rooms\" ORDER BY \"rooms\".\"created_at\" ASC LIMIT 1");
    }

    private static final String FOR_USER = SELECT
        + " INNER JOIN \"memberships\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\" WHERE \"memberships\".\"user_id\" = ?";

    /// `user.rooms.find_by(id:)`
    public static Room findForUser(Conn conn, long userId, long roomId) {
        return conn.one(FOR_USER + " AND \"rooms\".\"id\" = ? LIMIT 1", Room::from, userId, roomId);
    }

    /// `user.rooms.without_directs.find_by(id:)`
    public static Room findSharedForUser(Conn conn, long userId, long roomId) {
        return conn.one(FOR_USER + " AND \"rooms\".\"type\" != 'Rooms::Direct' AND \"rooms\".\"id\" = ? LIMIT 1", Room::from, userId, roomId);
    }

    /// `user.rooms.directs.find_by(id:)`
    public static Room findDirectForUser(Conn conn, long userId, long roomId) {
        return conn.one(FOR_USER + " AND \"rooms\".\"type\" = 'Rooms::Direct' AND \"rooms\".\"id\" = ? LIMIT 1", Room::from, userId, roomId);
    }

    public static boolean anyForUser(Conn conn, long userId) {
        return conn.exists("SELECT 1 FROM \"memberships\" WHERE \"memberships\".\"user_id\" = ? LIMIT 1", userId);
    }

    /// `user.rooms.original`
    public static Room originalForUser(Conn conn, long userId) {
        return conn.one(FOR_USER + " ORDER BY \"rooms\".\"created_at\" ASC LIMIT 1", Room::from, userId);
    }

    /// The room the user visited last (from the `last_room` cookie) if they're still in it, else
    /// their first room; null when they're in none.
    public static Room lastVisitedForUser(Conn conn, long userId, Long cookieRoomId) {
        Room room = cookieRoomId == null ? null : findForUser(conn, userId, cookieRoomId);
        return room != null ? room : originalForUser(conn, userId);
    }

    /// `user.rooms.last` (by primary key).
    public static Room lastForUser(Conn conn, long userId) {
        return conn.one(FOR_USER + " ORDER BY \"rooms\".\"id\" DESC LIMIT 1", Room::from, userId);
    }

    /// `bot.rooms.without_directs.ordered`
    public static List<Room> sharedForUserOrdered(Conn conn, long userId) {
        return conn.list(FOR_USER + " AND \"rooms\".\"type\" != 'Rooms::Direct' ORDER BY LOWER(name)", Room::from, userId);
    }

    public static List<Long> directIdsForUser(Conn conn, long userId) {
        return conn.longs("SELECT \"rooms\".\"id\" FROM \"rooms\" INNER JOIN \"memberships\" ON \"rooms\".\"id\" = \"memberships\".\"room_id\""
            + " WHERE \"memberships\".\"user_id\" = ? AND \"rooms\".\"type\" = 'Rooms::Direct'", userId);
    }

    public static List<Long> openIds(Conn conn) {
        return conn.longs("SELECT \"rooms\".\"id\" FROM \"rooms\" WHERE \"rooms\".\"type\" = 'Rooms::Open'");
    }

    public static List<Long> userIds(Conn conn, long roomId) {
        return conn.longs("SELECT \"memberships\".\"user_id\" FROM \"memberships\" WHERE \"memberships\".\"room_id\" = ? ORDER BY \"memberships\".\"id\"", roomId);
    }

    // -- Writes --------------------------------------------------------------------------------

    /// `Room.create_for(attributes, users:)`: the room and memberships for `users`.
    public static Room createFor(Tx tx, String type, String name, long creatorId, List<Long> userIds) {
        Instant now = Times.now();
        long id = tx.insert("INSERT INTO \"rooms\" (\"created_at\", \"creator_id\", \"name\", \"type\", \"updated_at\") VALUES (?, ?, ?, ?, ?)",
            now, creatorId, name, type, now);
        var room = new Room(id, name, type, creatorId, now, now);
        Membership.grantTo(tx, room, userIds);
        if (room.isOpen()) Membership.grantTo(tx, room, User.activeOrdered(tx).stream().map(User::id).toList());
        return room;
    }

    /// Updates name and type; a room that becomes open grants every active user access.
    public static Room update(Tx tx, Room room, String name, String type) {
        if (room.isDirect() && !DIRECT.equals(type)) throw new IllegalArgumentException("Type can't be changed for a direct room");
        Instant now = Times.now();
        tx.update("UPDATE \"rooms\" SET \"name\" = ?, \"type\" = ?, \"updated_at\" = ? WHERE \"rooms\".\"id\" = ?", name, type, now, room.id());
        var updated = new Room(room.id(), name, type, room.creatorId(), room.createdAt(), now);
        if (updated.isOpen() && !room.isOpen()) Membership.grantTo(tx, updated, User.activeOrdered(tx).stream().map(User::id).toList());
        return updated;
    }

    /// `belongs_to :room, touch: true`
    public static Instant touch(Tx tx, long roomId) {
        Instant now = Times.now();
        tx.update("UPDATE \"rooms\" SET \"updated_at\" = ? WHERE \"rooms\".\"id\" = ?", now, roomId);
        return now;
    }

    /// `room.destroy`: memberships deleted, messages destroyed with their dependents.
    public static List<Long> destroy(Tx tx, Room room) {
        List<Long> memberUserIds = userIds(tx, room.id());
        tx.update("DELETE FROM \"memberships\" WHERE \"memberships\".\"room_id\" = ?", room.id());
        for (var message : Message.forRoom(tx, room.id())) Message.destroy(tx, message, false);
        tx.update("DELETE FROM \"rooms\" WHERE \"rooms\".\"id\" = ?", room.id());
        return memberUserIds;
    }

    /// `Rooms::Direct.find_or_create_for(users)`: the direct room with exactly these users.
    public static Room findOrCreateDirect(Tx tx, List<Long> userIds, long creatorId) {
        var wanted = new java.util.TreeSet<>(userIds);
        for (var room : tx.list(SELECT + " WHERE \"rooms\".\"type\" = 'Rooms::Direct' ORDER BY \"rooms\".\"id\"", Room::from)) {
            if (new java.util.TreeSet<>(userIds(tx, room.id())).equals(wanted)) return room;
        }
        return createFor(tx, DIRECT, null, creatorId, new ArrayList<>(wanted));
    }
}
