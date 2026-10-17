package campfire.messages;

import static campfire.db.schema.Tables.BOOSTS;
import static campfire.db.schema.Tables.USERS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;

import campfire.users.User;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.jooq.Field;

/// A row of `boosts`: a reaction of at most 16 characters.
public record Boost(long id, long messageId, long boosterId, String content, Instant createdAt, Instant updatedAt) {
    /// The columns {@link #from} reads, in its order.
    static final Field<?>[] COLUMNS = {BOOSTS.ID, BOOSTS.MESSAGE_ID, BOOSTS.BOOSTER_ID, BOOSTS.CONTENT, BOOSTS.CREATED_AT, BOOSTS.UPDATED_AT};

    static Boost from(ResultSet rs, int offset) throws SQLException {
        return new Boost(rs.getLong(offset + 1), rs.getLong(offset + 2), rs.getLong(offset + 3), rs.getString(offset + 4),
            Conn.getTime(rs, offset + 5), Conn.getTime(rs, offset + 6));
    }

    public String domId() {
        return "boost_" + id;
    }

    /// A boost with its booster.
    public record WithBooster(Boost boost, User booster) {}

    private static final String FIND = Sql.render(select(COLUMNS).from(BOOSTS).where(BOOSTS.MESSAGE_ID.eq(param(BOOSTS.MESSAGE_ID)))
        .and(BOOSTS.ID.eq(param(BOOSTS.ID))).limit(inline(1)));
    private static final IntFunction<String> FOR_MESSAGES = Sql.perCount(n -> select(Stream.concat(Stream.of(COLUMNS), Stream.of(User.COLUMNS)).toList())
        .from(BOOSTS).join(USERS).on(USERS.ID.eq(BOOSTS.BOOSTER_ID)).where(BOOSTS.MESSAGE_ID.in(Sql.params(BOOSTS.MESSAGE_ID, n)))
        .orderBy(BOOSTS.CREATED_AT.asc()));
    private static final String INSERT = Sql.render(insertInto(BOOSTS, BOOSTS.BOOSTER_ID, BOOSTS.CONTENT, BOOSTS.CREATED_AT, BOOSTS.MESSAGE_ID,
            BOOSTS.UPDATED_AT)
        .values(param(BOOSTS.BOOSTER_ID), param(BOOSTS.CONTENT), param(BOOSTS.CREATED_AT), param(BOOSTS.MESSAGE_ID), param(BOOSTS.UPDATED_AT)));
    private static final String DELETE = Sql.render(deleteFrom(BOOSTS).where(BOOSTS.ID.eq(param(BOOSTS.ID))));

    public static Boost find(Conn conn, long messageId, long id) {
        return conn.one(FIND, rs -> from(rs, 0), messageId, id);
    }

    public static List<WithBooster> forMessage(Conn conn, long messageId) {
        return forMessages(conn, List.of(messageId)).getOrDefault(messageId, List.of());
    }

    /// `message.boosts.ordered` with boosters, for a page of messages.
    public static Map<Long, List<WithBooster>> forMessages(Conn conn, List<Long> messageIds) {
        var out = new LinkedHashMap<Long, List<WithBooster>>();
        if (messageIds.isEmpty()) return out;
        conn.list(FOR_MESSAGES.apply(messageIds.size()), rs -> {
            var boost = from(rs, 0);
            out.computeIfAbsent(boost.messageId(), k -> new ArrayList<>()).add(new WithBooster(boost, User.from(rs, COLUMNS.length)));
            return null;
        }, messageIds.toArray());
        return out;
    }

    public static Boost create(Tx tx, long messageId, long boosterId, String content) {
        Instant now = Times.now();
        long id = tx.insert(INSERT, boosterId, content, now, messageId, now);
        return new Boost(id, messageId, boosterId, content, now, now);
    }

    public static void delete(Tx tx, long id) {
        tx.update(DELETE, id);
    }
}
