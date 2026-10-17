package campfire.rooms;

import static campfire.db.schema.Tables.MEMBERSHIPS;
import static campfire.db.schema.Tables.ROOMS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import campfire.messages.Message;
import campfire.users.User;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.SelectConditionStep;

/// A row of `rooms`; `type` is `Rooms::Open`, `Rooms::Closed` or `Rooms::Direct` (STI).
public record Room(long id, String name, String type, long creatorId, Instant createdAt, Instant updatedAt) {
    public static final String OPEN = "Rooms::Open", CLOSED = "Rooms::Closed", DIRECT = "Rooms::Direct";

    /// The columns {@link #from} reads, in its order.
    static final Field<?>[] COLUMNS = {ROOMS.ID, ROOMS.NAME, ROOMS.TYPE, ROOMS.CREATOR_ID, ROOMS.CREATED_AT, ROOMS.UPDATED_AT};

    static Room from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    static Room from(ResultSet rs, int offset) throws SQLException {
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

    /// `user.rooms`; binds the user id.
    private static SelectConditionStep<?> forUser() {
        return select(COLUMNS).from(ROOMS).join(MEMBERSHIPS).on(ROOMS.ID.eq(MEMBERSHIPS.ROOM_ID)).where(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID)));
    }

    private static final String FIND = Sql.render(select(COLUMNS).from(ROOMS).where(ROOMS.ID.eq(param(ROOMS.ID))).limit(inline(1)));
    private static final String ORIGINAL = Sql.render(select(COLUMNS).from(ROOMS).orderBy(ROOMS.CREATED_AT.asc()).limit(inline(1)));
    private static final String ORIGINAL_ID = Sql.render(select(ROOMS.ID).from(ROOMS).orderBy(ROOMS.CREATED_AT.asc()).limit(inline(1)));
    private static final String FIND_FOR_USER = Sql.render(forUser().and(ROOMS.ID.eq(param(ROOMS.ID))).limit(inline(1)));
    private static final String FIND_SHARED_FOR_USER = Sql.render(forUser().and(ROOMS.TYPE.ne(inline(DIRECT))).and(ROOMS.ID.eq(param(ROOMS.ID))).limit(inline(1)));
    private static final String FIND_DIRECT_FOR_USER = Sql.render(forUser().and(ROOMS.TYPE.eq(inline(DIRECT))).and(ROOMS.ID.eq(param(ROOMS.ID))).limit(inline(1)));
    private static final String ANY_FOR_USER = Sql.render(selectOne().from(MEMBERSHIPS).where(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID))).limit(inline(1)));
    private static final String ORIGINAL_FOR_USER = Sql.render(forUser().orderBy(ROOMS.CREATED_AT.asc()).limit(inline(1)));
    private static final String LAST_FOR_USER = Sql.render(forUser().orderBy(ROOMS.ID.desc()).limit(inline(1)));
    private static final String SHARED_FOR_USER_ORDERED = Sql.render(forUser().and(ROOMS.TYPE.ne(inline(DIRECT))).orderBy(lower(ROOMS.NAME)));
    private static final String OPEN_IDS = Sql.render(select(ROOMS.ID).from(ROOMS).where(ROOMS.TYPE.eq(inline(OPEN))));
    private static final String USER_IDS = Sql.render(select(MEMBERSHIPS.USER_ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID)))
        .orderBy(MEMBERSHIPS.ID));
    private static final String DIRECTS = Sql.render(select(COLUMNS).from(ROOMS).where(ROOMS.TYPE.eq(inline(DIRECT))).orderBy(ROOMS.ID));

    public static Room find(Conn conn, long id) {
        return conn.one(FIND, Room::from, id);
    }

    /// `Room.original`: the first room created.
    public static Room original(Conn conn) {
        return conn.one(ORIGINAL, Room::from);
    }

    public static Long originalId(Conn conn) {
        return conn.longValue(ORIGINAL_ID);
    }

    /// `user.rooms.find_by(id:)`
    public static Room findForUser(Conn conn, long userId, long roomId) {
        return conn.one(FIND_FOR_USER, Room::from, userId, roomId);
    }

    /// `user.rooms.without_directs.find_by(id:)`
    public static Room findSharedForUser(Conn conn, long userId, long roomId) {
        return conn.one(FIND_SHARED_FOR_USER, Room::from, userId, roomId);
    }

    /// `user.rooms.directs.find_by(id:)`
    public static Room findDirectForUser(Conn conn, long userId, long roomId) {
        return conn.one(FIND_DIRECT_FOR_USER, Room::from, userId, roomId);
    }

    public static boolean anyForUser(Conn conn, long userId) {
        return conn.exists(ANY_FOR_USER, userId);
    }

    /// `user.rooms.original`
    public static Room originalForUser(Conn conn, long userId) {
        return conn.one(ORIGINAL_FOR_USER, Room::from, userId);
    }

    /// The room the user visited last (from the `last_room` cookie) if they're still in it, else
    /// their first room; null when they're in none.
    public static Room lastVisitedForUser(Conn conn, long userId, Long cookieRoomId) {
        Room room = cookieRoomId == null ? null : findForUser(conn, userId, cookieRoomId);
        return room != null ? room : originalForUser(conn, userId);
    }

    /// `user.rooms.last` (by primary key).
    public static Room lastForUser(Conn conn, long userId) {
        return conn.one(LAST_FOR_USER, Room::from, userId);
    }

    /// `bot.rooms.without_directs.ordered`
    public static List<Room> sharedForUserOrdered(Conn conn, long userId) {
        return conn.list(SHARED_FOR_USER_ORDERED, Room::from, userId);
    }

    public static List<Long> openIds(Conn conn) {
        return conn.longs(OPEN_IDS);
    }

    public static List<Long> userIds(Conn conn, long roomId) {
        return conn.longs(USER_IDS, roomId);
    }

    // -- Writes --------------------------------------------------------------------------------

    private static final String INSERT = Sql.render(insertInto(ROOMS, ROOMS.CREATED_AT, ROOMS.CREATOR_ID, ROOMS.NAME, ROOMS.TYPE, ROOMS.UPDATED_AT)
        .values(param(ROOMS.CREATED_AT), param(ROOMS.CREATOR_ID), param(ROOMS.NAME), param(ROOMS.TYPE), param(ROOMS.UPDATED_AT)));
    private static final String UPDATE = Sql.render(DSL.update(ROOMS).set(ROOMS.NAME, param(ROOMS.NAME)).set(ROOMS.TYPE, param(ROOMS.TYPE))
        .set(ROOMS.UPDATED_AT, param(ROOMS.UPDATED_AT)).where(ROOMS.ID.eq(param(ROOMS.ID))));
    private static final String TOUCH = Sql.render(DSL.update(ROOMS).set(ROOMS.UPDATED_AT, param(ROOMS.UPDATED_AT)).where(ROOMS.ID.eq(param(ROOMS.ID))));
    private static final String DELETE_MEMBERSHIPS = Sql.render(deleteFrom(MEMBERSHIPS).where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID))));
    private static final String DELETE = Sql.render(deleteFrom(ROOMS).where(ROOMS.ID.eq(param(ROOMS.ID))));

    /// `Room.create_for(attributes, users:)`: the room and memberships for `users`.
    public static Room createFor(Tx tx, String type, String name, long creatorId, List<Long> userIds) {
        Instant now = Times.now();
        long id = tx.insert(INSERT, now, creatorId, name, type, now);
        var room = new Room(id, name, type, creatorId, now, now);
        Membership.grantTo(tx, room, userIds);
        if (room.isOpen()) Membership.grantTo(tx, room, User.activeOrdered(tx).stream().map(User::id).toList());
        return room;
    }

    /// Updates name and type; a room that becomes open grants every active user access.
    public static Room update(Tx tx, Room room, String name, String type) {
        if (room.isDirect() && !DIRECT.equals(type)) throw new IllegalArgumentException("Type can't be changed for a direct room");
        Instant now = Times.now();
        tx.update(UPDATE, name, type, now, room.id());
        var updated = new Room(room.id(), name, type, room.creatorId(), room.createdAt(), now);
        if (updated.isOpen() && !room.isOpen()) Membership.grantTo(tx, updated, User.activeOrdered(tx).stream().map(User::id).toList());
        return updated;
    }

    /// `belongs_to :room, touch: true`
    public static Instant touch(Tx tx, long roomId) {
        Instant now = Times.now();
        tx.update(TOUCH, now, roomId);
        return now;
    }

    /// `room.destroy`: memberships deleted, messages destroyed with their dependents.
    public static List<Long> destroy(Tx tx, Room room) {
        List<Long> memberUserIds = userIds(tx, room.id());
        tx.update(DELETE_MEMBERSHIPS, room.id());
        for (var message : Message.forRoom(tx, room.id())) Message.destroy(tx, message, false);
        tx.update(DELETE, room.id());
        return memberUserIds;
    }

    /// `Rooms::Direct.find_or_create_for(users)`: the direct room with exactly these users.
    public static Room findOrCreateDirect(Tx tx, List<Long> userIds, long creatorId) {
        var wanted = new java.util.TreeSet<>(userIds);
        for (var room : tx.list(DIRECTS, Room::from)) {
            if (new java.util.TreeSet<>(userIds(tx, room.id())).equals(wanted)) return room;
        }
        return createFor(tx, DIRECT, null, creatorId, new ArrayList<>(wanted));
    }
}
