package campfire.rooms;

import static campfire.db.schema.Tables.MEMBERSHIPS;
import static campfire.db.schema.Tables.ROOMS;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.update;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import campfire.db.schema.tables.Memberships;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jooq.Field;
import org.jooq.SelectConditionStep;

/// A row of `memberships`. `involvement` is `invisible`, `nothing`, `mentions` or `everything`.
public record Membership(long id, long roomId, long userId, String involvement, Instant unreadAt, Instant connectedAt, int connections,
                         Instant createdAt, Instant updatedAt) {
    /// `Membership::Connectable::CONNECTION_TTL`
    public static final Duration CONNECTION_TTL = Duration.ofSeconds(60);

    /// The columns {@link #from} reads, in its order.
    static final Field<?>[] COLUMNS = {MEMBERSHIPS.ID, MEMBERSHIPS.ROOM_ID, MEMBERSHIPS.USER_ID, MEMBERSHIPS.INVOLVEMENT, MEMBERSHIPS.UNREAD_AT,
        MEMBERSHIPS.CONNECTED_AT, MEMBERSHIPS.CONNECTIONS, MEMBERSHIPS.CREATED_AT, MEMBERSHIPS.UPDATED_AT};

    static Membership from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    static Membership from(ResultSet rs, int offset) throws SQLException {
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

    /// `user.memberships` with their rooms; binds the user id.
    private static SelectConditionStep<?> withRoom() {
        return select(Stream.concat(Stream.of(COLUMNS), Stream.of(Room.COLUMNS)).toList()).from(MEMBERSHIPS).join(ROOMS)
            .on(ROOMS.ID.eq(MEMBERSHIPS.ROOM_ID)).where(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID)));
    }

    private static WithRoom withRoom(ResultSet rs) throws SQLException {
        return new WithRoom(from(rs, 0), Room.from(rs, COLUMNS.length));
    }

    private static final String FIND = Sql.render(select(COLUMNS).from(MEMBERSHIPS).where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID)))
        .and(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID))).limit(inline(1)));
    private static final String EXISTS = Sql.render(selectOne().from(MEMBERSHIPS).where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID)))
        .and(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID))).limit(inline(1)));
    private static final String WITH_ORDERED_ROOM = Sql.render(withRoom().orderBy(lower(ROOMS.NAME)));
    private static final String VISIBLE_WITH_ORDERED_ROOM = Sql.render(withRoom().and(MEMBERSHIPS.INVOLVEMENT.ne(inline("invisible"))).orderBy(lower(ROOMS.NAME)));
    private static final String FOR_ROOM = Sql.render(select(COLUMNS).from(MEMBERSHIPS).where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID)))
        .orderBy(MEMBERSHIPS.ID));
    private static final String UNREAD_COUNT = Sql.render(select(count()).from(MEMBERSHIPS).where(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID)))
        .and(MEMBERSHIPS.UNREAD_AT.isNotNull()));
    private static final Memberships MINE = MEMBERSHIPS.as("mine");
    /// The members of the user's direct rooms.
    private static final String IN_DIRECT_ROOMS_WITH = Sql.render(selectDistinct(MEMBERSHIPS.USER_ID).from(MEMBERSHIPS).where(MEMBERSHIPS.ROOM_ID.in(
        select(ROOMS.ID).from(ROOMS).join(MINE).on(ROOMS.ID.eq(MINE.ROOM_ID))
            .where(MINE.USER_ID.eq(param(MINE.USER_ID))).and(ROOMS.TYPE.eq(inline(Room.DIRECT))))));

    public static Membership find(Conn conn, long roomId, long userId) {
        return conn.one(FIND, Membership::from, roomId, userId);
    }

    public static boolean exists(Conn conn, long roomId, long userId) {
        return conn.exists(EXISTS, roomId, userId);
    }

    /// `user.memberships.with_ordered_room`
    public static List<WithRoom> withOrderedRoom(Conn conn, long userId) {
        return conn.list(WITH_ORDERED_ROOM, Membership::withRoom, userId);
    }

    /// `user.memberships.visible.with_ordered_room`
    public static List<WithRoom> visibleWithOrderedRoom(Conn conn, long userId) {
        return conn.list(VISIBLE_WITH_ORDERED_ROOM, Membership::withRoom, userId);
    }

    public static List<Membership> forRoom(Conn conn, long roomId) {
        return conn.list(FOR_ROOM, Membership::from, roomId);
    }

    /// `user.memberships.unread.count`, the push badge.
    public static long unreadCount(Conn conn, long userId) {
        return conn.count(UNREAD_COUNT, userId);
    }

    /// User ids sharing a direct room with `userId` (including `userId`).
    public static List<Long> userIdsInDirectRoomsWith(Conn conn, long userId) {
        return conn.longs(IN_DIRECT_ROOMS_WITH, userId);
    }

    // -- Writes --------------------------------------------------------------------------------

    /// `insert_all`'s skip of an existing membership.
    private static final String GRANT = Sql.render(insertInto(MEMBERSHIPS, MEMBERSHIPS.ROOM_ID, MEMBERSHIPS.USER_ID, MEMBERSHIPS.INVOLVEMENT,
            MEMBERSHIPS.CREATED_AT, MEMBERSHIPS.UPDATED_AT)
        .values(param(MEMBERSHIPS.ROOM_ID), param(MEMBERSHIPS.USER_ID), param(MEMBERSHIPS.INVOLVEMENT), param(MEMBERSHIPS.CREATED_AT), param(MEMBERSHIPS.UPDATED_AT))
        .onConflictDoNothing());
    private static final String REVOKE = Sql.render(deleteFrom(MEMBERSHIPS).where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID)))
        .and(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID))));
    private static final String UPDATE_INVOLVEMENT = Sql.render(update(MEMBERSHIPS).set(MEMBERSHIPS.INVOLVEMENT, param(MEMBERSHIPS.INVOLVEMENT))
        .set(MEMBERSHIPS.UPDATED_AT, param(MEMBERSHIPS.UPDATED_AT)).where(MEMBERSHIPS.ID.eq(param(MEMBERSHIPS.ID))));
    /// Binds unread at, updated at, room, connected-before and creator.
    private static final String MARK_UNREAD = Sql.render(update(MEMBERSHIPS).set(MEMBERSHIPS.UNREAD_AT, param(MEMBERSHIPS.UNREAD_AT))
        .set(MEMBERSHIPS.UPDATED_AT, param(MEMBERSHIPS.UPDATED_AT))
        .where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID)))
        .and(MEMBERSHIPS.INVOLVEMENT.ne(inline("invisible")))
        .and(MEMBERSHIPS.CONNECTED_AT.isNull().or(MEMBERSHIPS.CONNECTED_AT.lt(param(MEMBERSHIPS.CONNECTED_AT))))
        .and(MEMBERSHIPS.USER_ID.ne(param(MEMBERSHIPS.USER_ID))));
    private static final String DISCONNECT_ALL = Sql.render(update(MEMBERSHIPS).set(MEMBERSHIPS.CONNECTED_AT, inline(null, MEMBERSHIPS.CONNECTED_AT)).set(MEMBERSHIPS.CONNECTIONS, inline(0))
        .set(MEMBERSHIPS.UPDATED_AT, param(MEMBERSHIPS.UPDATED_AT)).where(MEMBERSHIPS.CONNECTED_AT.ge(param(MEMBERSHIPS.CONNECTED_AT))));
    private static final String PRESENT = Sql.render(update(MEMBERSHIPS).set(MEMBERSHIPS.CONNECTIONS, param(MEMBERSHIPS.CONNECTIONS))
        .set(MEMBERSHIPS.CONNECTED_AT, param(MEMBERSHIPS.CONNECTED_AT)).set(MEMBERSHIPS.UNREAD_AT, inline(null, MEMBERSHIPS.UNREAD_AT)).where(MEMBERSHIPS.ID.eq(param(MEMBERSHIPS.ID))));
    private static final String DISCONNECTED = Sql.render(update(MEMBERSHIPS).set(MEMBERSHIPS.CONNECTIONS, param(MEMBERSHIPS.CONNECTIONS))
        .set(MEMBERSHIPS.CONNECTED_AT, inline(null, MEMBERSHIPS.CONNECTED_AT)).set(MEMBERSHIPS.UPDATED_AT, param(MEMBERSHIPS.UPDATED_AT)).where(MEMBERSHIPS.ID.eq(param(MEMBERSHIPS.ID))));
    private static final String STILL_CONNECTED = Sql.render(update(MEMBERSHIPS).set(MEMBERSHIPS.CONNECTIONS, param(MEMBERSHIPS.CONNECTIONS))
        .set(MEMBERSHIPS.UPDATED_AT, param(MEMBERSHIPS.UPDATED_AT)).where(MEMBERSHIPS.ID.eq(param(MEMBERSHIPS.ID))));
    private static final String REFRESH = Sql.render(update(MEMBERSHIPS).set(MEMBERSHIPS.CONNECTIONS, param(MEMBERSHIPS.CONNECTIONS))
        .set(MEMBERSHIPS.CONNECTED_AT, param(MEMBERSHIPS.CONNECTED_AT)).set(MEMBERSHIPS.UPDATED_AT, param(MEMBERSHIPS.UPDATED_AT))
        .where(MEMBERSHIPS.ID.eq(param(MEMBERSHIPS.ID))));

    /// `room.memberships.grant_to(users)`: `insert_all`, skipping existing memberships.
    public static void grantTo(Tx tx, Room room, List<Long> userIds) {
        Instant now = Times.now();
        for (long userId : userIds) tx.update(GRANT, room.id(), userId, room.defaultInvolvement(), now, now);
    }

    /// `room.memberships.revoke_from(users)`; returns the users who lost access.
    public static List<Long> revokeFrom(Tx tx, long roomId, List<Long> userIds) {
        var revoked = new java.util.ArrayList<Long>();
        for (long userId : userIds) {
            if (tx.update(REVOKE, roomId, userId) > 0) revoked.add(userId);
        }
        return revoked;
    }

    public static void updateInvolvement(Tx tx, long id, String involvement) {
        tx.update(UPDATE_INVOLVEMENT, involvement, Times.now(), id);
    }

    /// `Room#unread_memberships`: visible, disconnected memberships other than the creator's.
    public static void markUnread(Tx tx, long roomId, long creatorId, Instant messageCreatedAt) {
        Instant now = Times.now();
        tx.update(MARK_UNREAD, messageCreatedAt, now, roomId, now.minus(CONNECTION_TTL), creatorId);
    }

    /// `Membership.disconnect_all` at boot.
    public static void disconnectAll(Tx tx) {
        Instant now = Times.now();
        tx.update(DISCONNECT_ALL, now, now.minus(CONNECTION_TTL));
    }

    /// `membership.present`: connected, read.
    public static void present(Tx tx, long roomId, long userId) {
        var m = find(tx, roomId, userId);
        if (m == null) return;
        Instant now = Times.now();
        int connections = m.isConnected(now) ? m.connections() + 1 : 1;
        tx.update(PRESENT, connections, now, m.id());
    }

    /// `membership.disconnected`
    public static void disconnected(Tx tx, long roomId, long userId) {
        var m = find(tx, roomId, userId);
        if (m == null) return;
        Instant now = Times.now();
        int connections = m.isConnected(now) ? m.connections() - 1 : 0;
        tx.update(connections < 1 ? DISCONNECTED : STILL_CONNECTED, connections, now, m.id());
    }

    /// `membership.refresh_connection`
    public static void refreshConnection(Tx tx, long roomId, long userId) {
        var m = find(tx, roomId, userId);
        if (m == null) return;
        Instant now = Times.now();
        int connections = m.isConnected(now) ? m.connections() : 1;
        tx.update(REFRESH, connections, now, now, m.id());
    }
}
