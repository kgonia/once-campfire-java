package campfire.search;

import static campfire.db.schema.Tables.SEARCHES;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.update;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import java.time.Instant;
import java.util.List;

/// A row of `searches`: a user's recent search queries (the ten newest are kept).
public record Search(long id, long userId, String query, Instant updatedAt) {
    private static final String RECENT_FOR = Sql.render(select(SEARCHES.ID, SEARCHES.USER_ID, SEARCHES.QUERY, SEARCHES.UPDATED_AT).from(SEARCHES)
        .where(SEARCHES.USER_ID.eq(param(SEARCHES.USER_ID))).orderBy(SEARCHES.UPDATED_AT.desc()));
    private static final String FIND_ID = Sql.render(select(SEARCHES.ID).from(SEARCHES).where(SEARCHES.USER_ID.eq(param(SEARCHES.USER_ID)))
        .and(SEARCHES.QUERY.eq(param(SEARCHES.QUERY))).limit(inline(1)));
    private static final String INSERT = Sql.render(insertInto(SEARCHES, SEARCHES.CREATED_AT, SEARCHES.QUERY, SEARCHES.UPDATED_AT, SEARCHES.USER_ID)
        .values(param(SEARCHES.CREATED_AT), param(SEARCHES.QUERY), param(SEARCHES.UPDATED_AT), param(SEARCHES.USER_ID)));
    /// Binds the user twice.
    private static final String KEEP_TEN_NEWEST = Sql.render(deleteFrom(SEARCHES).where(SEARCHES.USER_ID.eq(param(SEARCHES.USER_ID)))
        .and(SEARCHES.ID.notIn(select(SEARCHES.ID).from(SEARCHES).where(SEARCHES.USER_ID.eq(param(SEARCHES.USER_ID)))
            .orderBy(SEARCHES.UPDATED_AT.desc()).limit(inline(10)))));
    private static final String TOUCH = Sql.render(update(SEARCHES).set(SEARCHES.UPDATED_AT, param(SEARCHES.UPDATED_AT))
        .where(SEARCHES.ID.eq(param(SEARCHES.ID))));
    private static final String CLEAR = Sql.render(deleteFrom(SEARCHES).where(SEARCHES.USER_ID.eq(param(SEARCHES.USER_ID))));

    public static List<Search> recentFor(Conn conn, long userId) {
        return conn.list(RECENT_FOR, rs -> new Search(rs.getLong(1), rs.getLong(2), rs.getString(3), Conn.getTime(rs, 4)), userId);
    }

    /// `user.searches.record(query)`: find or create, touch, keep the ten newest.
    public static void record(Tx tx, long userId, String query) {
        Instant now = Times.now();
        Long id = tx.longValue(FIND_ID, userId, query);
        if (id == null) {
            tx.insert(INSERT, now, query, now, userId);
            tx.update(KEEP_TEN_NEWEST, userId, userId);
        } else {
            tx.update(TOUCH, now, id);
        }
    }

    public static void clear(Tx tx, long userId) {
        tx.update(CLEAR, userId);
    }
}
