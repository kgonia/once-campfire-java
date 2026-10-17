package campfire.push;

import static campfire.db.schema.Tables.MEMBERSHIPS;
import static campfire.db.schema.Tables.PUSH_SUBSCRIPTIONS;
import static campfire.db.schema.Tables.USERS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.update;

import campfire.rooms.Membership;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import org.jooq.Condition;
import org.jooq.Field;

/// A row of `push_subscriptions`: a browser's Web Push endpoint and keys.
public record PushSubscription(long id, long userId, String endpoint, String p256dhKey, String authKey, String userAgent) {
    private static final Field<?>[] COLUMNS = {PUSH_SUBSCRIPTIONS.ID, PUSH_SUBSCRIPTIONS.USER_ID, PUSH_SUBSCRIPTIONS.ENDPOINT, PUSH_SUBSCRIPTIONS.P256DH_KEY,
        PUSH_SUBSCRIPTIONS.AUTH_KEY, PUSH_SUBSCRIPTIONS.USER_AGENT};

    static PushSubscription from(ResultSet rs) throws SQLException {
        return new PushSubscription(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6));
    }

    private static final String FOR_USER = Sql.render(select(COLUMNS).from(PUSH_SUBSCRIPTIONS)
        .where(PUSH_SUBSCRIPTIONS.USER_ID.eq(param(PUSH_SUBSCRIPTIONS.USER_ID))).orderBy(PUSH_SUBSCRIPTIONS.ID));
    private static final String FIND = Sql.render(select(COLUMNS).from(PUSH_SUBSCRIPTIONS)
        .where(PUSH_SUBSCRIPTIONS.USER_ID.eq(param(PUSH_SUBSCRIPTIONS.USER_ID))).and(PUSH_SUBSCRIPTIONS.ID.eq(param(PUSH_SUBSCRIPTIONS.ID))).limit(inline(1)));
    /// `IS`, so a null key matches a NULL column.
    private static final String FIND_BY_KEYS = Sql.render(select(COLUMNS).from(PUSH_SUBSCRIPTIONS)
        .where(PUSH_SUBSCRIPTIONS.USER_ID.eq(param(PUSH_SUBSCRIPTIONS.USER_ID)))
        .and(PUSH_SUBSCRIPTIONS.ENDPOINT.isNotDistinctFrom(param(PUSH_SUBSCRIPTIONS.ENDPOINT)))
        .and(PUSH_SUBSCRIPTIONS.P256DH_KEY.isNotDistinctFrom(param(PUSH_SUBSCRIPTIONS.P256DH_KEY)))
        .and(PUSH_SUBSCRIPTIONS.AUTH_KEY.isNotDistinctFrom(param(PUSH_SUBSCRIPTIONS.AUTH_KEY))).limit(inline(1)));
    /// Binds room, connected-before, creator, then the mentioned user ids (none: only `everything`).
    private static final IntFunction<String> FOR_ROOM_DELIVERY = Sql.perCount(mentioned -> {
        Condition involved = MEMBERSHIPS.INVOLVEMENT.eq(inline("everything"));
        if (mentioned > 0) {
            involved = involved.or(MEMBERSHIPS.INVOLVEMENT.eq(inline("mentions"))
                .and(PUSH_SUBSCRIPTIONS.USER_ID.in(Sql.params(PUSH_SUBSCRIPTIONS.USER_ID, mentioned))));
        }
        return select(COLUMNS).from(PUSH_SUBSCRIPTIONS).join(USERS).on(USERS.ID.eq(PUSH_SUBSCRIPTIONS.USER_ID))
            .join(MEMBERSHIPS).on(MEMBERSHIPS.USER_ID.eq(USERS.ID))
            .where(MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID)))
            .and(MEMBERSHIPS.INVOLVEMENT.ne(inline("invisible")))
            .and(MEMBERSHIPS.CONNECTED_AT.isNull().or(MEMBERSHIPS.CONNECTED_AT.lt(param(MEMBERSHIPS.CONNECTED_AT))))
            .and(MEMBERSHIPS.USER_ID.ne(param(MEMBERSHIPS.USER_ID)))
            .and(involved)
            .orderBy(PUSH_SUBSCRIPTIONS.ID);
    });
    private static final String INSERT = Sql.render(insertInto(PUSH_SUBSCRIPTIONS, PUSH_SUBSCRIPTIONS.AUTH_KEY, PUSH_SUBSCRIPTIONS.CREATED_AT,
            PUSH_SUBSCRIPTIONS.ENDPOINT, PUSH_SUBSCRIPTIONS.P256DH_KEY, PUSH_SUBSCRIPTIONS.UPDATED_AT, PUSH_SUBSCRIPTIONS.USER_AGENT, PUSH_SUBSCRIPTIONS.USER_ID)
        .values(param(PUSH_SUBSCRIPTIONS.AUTH_KEY), param(PUSH_SUBSCRIPTIONS.CREATED_AT), param(PUSH_SUBSCRIPTIONS.ENDPOINT),
            param(PUSH_SUBSCRIPTIONS.P256DH_KEY), param(PUSH_SUBSCRIPTIONS.UPDATED_AT), param(PUSH_SUBSCRIPTIONS.USER_AGENT), param(PUSH_SUBSCRIPTIONS.USER_ID)));
    private static final String TOUCH = Sql.render(update(PUSH_SUBSCRIPTIONS).set(PUSH_SUBSCRIPTIONS.UPDATED_AT, param(PUSH_SUBSCRIPTIONS.UPDATED_AT))
        .where(PUSH_SUBSCRIPTIONS.ID.eq(param(PUSH_SUBSCRIPTIONS.ID))));
    private static final String DELETE = Sql.render(deleteFrom(PUSH_SUBSCRIPTIONS).where(PUSH_SUBSCRIPTIONS.ID.eq(param(PUSH_SUBSCRIPTIONS.ID))));
    private static final String DELETE_FOR_USER_BY_ID = Sql.render(deleteFrom(PUSH_SUBSCRIPTIONS)
        .where(PUSH_SUBSCRIPTIONS.USER_ID.eq(param(PUSH_SUBSCRIPTIONS.USER_ID))).and(PUSH_SUBSCRIPTIONS.ID.eq(param(PUSH_SUBSCRIPTIONS.ID))));
    private static final String DELETE_FOR_USER_BY_ENDPOINT = Sql.render(deleteFrom(PUSH_SUBSCRIPTIONS)
        .where(PUSH_SUBSCRIPTIONS.USER_ID.eq(param(PUSH_SUBSCRIPTIONS.USER_ID))).and(PUSH_SUBSCRIPTIONS.ENDPOINT.eq(param(PUSH_SUBSCRIPTIONS.ENDPOINT))));
    private static final String DELETE_ALL_FOR = Sql.render(deleteFrom(PUSH_SUBSCRIPTIONS)
        .where(PUSH_SUBSCRIPTIONS.USER_ID.eq(param(PUSH_SUBSCRIPTIONS.USER_ID))));

    public static List<PushSubscription> forUser(Conn conn, long userId) {
        return conn.list(FOR_USER, PushSubscription::from, userId);
    }

    public static PushSubscription find(Conn conn, long userId, long id) {
        return conn.one(FIND, PushSubscription::from, userId, id);
    }

    public static PushSubscription findByKeys(Conn conn, long userId, String endpoint, String p256dh, String auth) {
        return conn.one(FIND_BY_KEYS, PushSubscription::from, userId, endpoint, p256dh, auth);
    }

    private static final Map<String, Field<String>> KEY_COLUMNS = Map.of("endpoint", PUSH_SUBSCRIPTIONS.ENDPOINT, "p256dh_key",
        PUSH_SUBSCRIPTIONS.P256DH_KEY, "auth_key", PUSH_SUBSCRIPTIONS.AUTH_KEY);
    /// By which columns are given and which of them are null: at most 27 shapes.
    private static final ConcurrentHashMap<String, String> MATCHING = new ConcurrentHashMap<>();

    /// The id of the user's subscription matching the given columns (a null value matches NULL),
    /// columns not given matching anything.
    public static Long idMatching(Conn conn, long userId, Map<String, String> columns) {
        var shape = new StringBuilder();
        var args = new ArrayList<Object>(List.of(userId));
        columns.forEach((column, value) -> {
            shape.append(column).append(value == null ? " null," : " ?,");
            if (value != null) args.add(value);
        });
        String sql = MATCHING.computeIfAbsent(shape.toString(), key -> {
            Condition matching = PUSH_SUBSCRIPTIONS.USER_ID.eq(param(PUSH_SUBSCRIPTIONS.USER_ID));
            for (var entry : columns.entrySet()) {
                Field<String> field = KEY_COLUMNS.get(entry.getKey());
                if (field == null) throw new IllegalArgumentException("not a subscription key: " + entry.getKey());
                matching = matching.and(entry.getValue() == null ? field.isNull() : field.eq(param(field)));
            }
            return Sql.render(select(PUSH_SUBSCRIPTIONS.ID).from(PUSH_SUBSCRIPTIONS).where(matching).limit(inline(1)));
        });
        return conn.one(sql, rs -> rs.getLong(1), args.toArray());
    }

    /// Subscriptions of a room's visible, disconnected members other than the creator, whose
    /// involvement is `everything`, or `mentions` and they're among `mentionedUserIds`.
    public static List<PushSubscription> forRoomDelivery(Conn conn, long roomId, long creatorId, List<Long> mentionedUserIds, Instant now) {
        var args = new java.util.ArrayList<Object>(List.of(roomId, now.minus(Membership.CONNECTION_TTL), creatorId));
        args.addAll(mentionedUserIds);
        return conn.list(FOR_ROOM_DELIVERY.apply(mentionedUserIds.size()), PushSubscription::from, args.toArray());
    }

    public static void create(Tx tx, long userId, String endpoint, String p256dh, String auth, String userAgent) {
        Instant now = Times.now();
        tx.insert(INSERT, auth, now, endpoint, p256dh, now, userAgent, userId);
    }

    public static void touch(Tx tx, long id) {
        tx.update(TOUCH, Times.now(), id);
    }

    public static void delete(Tx tx, long id) {
        tx.update(DELETE, id);
    }

    public static void deleteForUser(Tx tx, long userId, Long id, String endpoint) {
        if (id != null) tx.update(DELETE_FOR_USER_BY_ID, userId, id);
        if (endpoint != null) tx.update(DELETE_FOR_USER_BY_ENDPOINT, userId, endpoint);
    }

    public static void deleteAllFor(Tx tx, long userId) {
        tx.update(DELETE_ALL_FOR, userId);
    }
}
