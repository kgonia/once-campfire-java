package campfire.sessions;

import static campfire.db.schema.Tables.SESSIONS;
import static campfire.db.schema.Tables.USERS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.update;

import campfire.users.User;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import campfire.rails.SecureRandoms;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jooq.Field;

/// A row of `sessions`: a signed-in browser, identified by its `session_token` cookie.
public record Session(long id, long userId, String token, String ipAddress, String userAgent, Instant lastActiveAt, Instant createdAt,
                      Instant updatedAt) {
    /// `Session::ACTIVITY_REFRESH_RATE`
    public static final Duration ACTIVITY_REFRESH_RATE = Duration.ofHours(1);

    /// The columns {@link #from} reads, in its order.
    static final Field<?>[] COLUMNS = {SESSIONS.ID, SESSIONS.USER_ID, SESSIONS.TOKEN, SESSIONS.IP_ADDRESS, SESSIONS.USER_AGENT, SESSIONS.LAST_ACTIVE_AT,
        SESSIONS.CREATED_AT, SESSIONS.UPDATED_AT};

    static Session from(ResultSet rs, int offset) throws SQLException {
        return new Session(rs.getLong(offset + 1), rs.getLong(offset + 2), rs.getString(offset + 3), rs.getString(offset + 4),
            rs.getString(offset + 5), Conn.getTime(rs, offset + 6), Conn.getTime(rs, offset + 7), Conn.getTime(rs, offset + 8));
    }

    /// A session with its user, as authentication loads them.
    public record WithUser(Session session, User user) {}

    private static final String FIND_BY_TOKEN = Sql.render(select(Stream.concat(Stream.of(COLUMNS), Stream.of(User.COLUMNS)).toList()).from(SESSIONS)
        .join(USERS).on(USERS.ID.eq(SESSIONS.USER_ID)).where(SESSIONS.TOKEN.eq(param(SESSIONS.TOKEN))).limit(inline(1)));
    private static final String IP_ADDRESSES_FOR = Sql.render(selectDistinct(SESSIONS.IP_ADDRESS).from(SESSIONS)
        .where(SESSIONS.USER_ID.eq(param(SESSIONS.USER_ID))).and(SESSIONS.IP_ADDRESS.isNotNull()).and(SESSIONS.IP_ADDRESS.ne(inline(""))));
    private static final String INSERT = Sql.render(insertInto(SESSIONS, SESSIONS.CREATED_AT, SESSIONS.IP_ADDRESS, SESSIONS.LAST_ACTIVE_AT,
            SESSIONS.TOKEN, SESSIONS.UPDATED_AT, SESSIONS.USER_AGENT, SESSIONS.USER_ID)
        .values(param(SESSIONS.CREATED_AT), param(SESSIONS.IP_ADDRESS), param(SESSIONS.LAST_ACTIVE_AT), param(SESSIONS.TOKEN),
            param(SESSIONS.UPDATED_AT), param(SESSIONS.USER_AGENT), param(SESSIONS.USER_ID)));
    private static final String RESUME = Sql.render(update(SESSIONS).set(SESSIONS.USER_AGENT, param(SESSIONS.USER_AGENT))
        .set(SESSIONS.IP_ADDRESS, param(SESSIONS.IP_ADDRESS)).set(SESSIONS.LAST_ACTIVE_AT, param(SESSIONS.LAST_ACTIVE_AT))
        .set(SESSIONS.UPDATED_AT, param(SESSIONS.UPDATED_AT)).where(SESSIONS.ID.eq(param(SESSIONS.ID))));
    private static final String DELETE = Sql.render(deleteFrom(SESSIONS).where(SESSIONS.ID.eq(param(SESSIONS.ID))));
    private static final String DELETE_ALL_FOR = Sql.render(deleteFrom(SESSIONS).where(SESSIONS.USER_ID.eq(param(SESSIONS.USER_ID))));

    public static WithUser findByToken(Conn conn, String token) {
        return conn.one(FIND_BY_TOKEN, rs -> new WithUser(from(rs, 0), User.from(rs, COLUMNS.length)), token);
    }

    public static List<String> ipAddressesFor(Conn conn, long userId) {
        return conn.list(IP_ADDRESSES_FOR, rs -> rs.getString(1), userId);
    }

    /// `user.sessions.start!(user_agent:, ip_address:)`
    public static Session start(Tx tx, long userId, String userAgent, String ipAddress) {
        Instant now = Times.now();
        String token = SecureRandoms.base58(24); // has_secure_token
        long id = tx.insert(INSERT, now, ipAddress, now, token, now, userAgent, userId);
        return new Session(id, userId, token, ipAddress, userAgent, now, now, now);
    }

    /// `session.resume`: true when the hourly activity refresh is due.
    public boolean needsResume(Instant now) {
        return lastActiveAt == null || lastActiveAt.isBefore(now.minus(ACTIVITY_REFRESH_RATE));
    }

    public static void resume(Tx tx, long id, String userAgent, String ipAddress) {
        Instant now = Times.now();
        tx.update(RESUME, userAgent, ipAddress, now, now, id);
    }

    public static void delete(Tx tx, long id) {
        tx.update(DELETE, id);
    }

    public static void deleteAllFor(Tx tx, long userId) {
        tx.update(DELETE_ALL_FOR, userId);
    }
}
