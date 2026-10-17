package campfire.bots;

import static campfire.db.schema.Tables.WEBHOOKS;
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

/// A row of `webhooks`: where a bot is told about messages that concern it.
public record Webhook(long id, long userId, String url) {
    private static final String FOR_USER = Sql.render(select(WEBHOOKS.ID, WEBHOOKS.USER_ID, WEBHOOKS.URL).from(WEBHOOKS)
        .where(WEBHOOKS.USER_ID.eq(param(WEBHOOKS.USER_ID))).orderBy(WEBHOOKS.ID).limit(inline(1)));
    private static final String DELETE = Sql.render(deleteFrom(WEBHOOKS).where(WEBHOOKS.ID.eq(param(WEBHOOKS.ID))));
    private static final String UPDATE_URL = Sql.render(update(WEBHOOKS).set(WEBHOOKS.UPDATED_AT, param(WEBHOOKS.UPDATED_AT))
        .set(WEBHOOKS.URL, param(WEBHOOKS.URL)).where(WEBHOOKS.ID.eq(param(WEBHOOKS.ID))));
    private static final String INSERT = Sql.render(insertInto(WEBHOOKS, WEBHOOKS.CREATED_AT, WEBHOOKS.UPDATED_AT, WEBHOOKS.URL, WEBHOOKS.USER_ID)
        .values(param(WEBHOOKS.CREATED_AT), param(WEBHOOKS.UPDATED_AT), param(WEBHOOKS.URL), param(WEBHOOKS.USER_ID)));

    public static Webhook forUser(Conn conn, long userId) {
        return conn.one(FOR_USER, rs -> new Webhook(rs.getLong(1), rs.getLong(2), rs.getString(3)), userId);
    }

    /// Sets, replaces or (for a blank url) removes a bot's webhook; the same url writes nothing.
    public static void set(Tx tx, long userId, String url) {
        var existing = forUser(tx, userId);
        if (url == null || url.isBlank()) {
            if (existing != null) tx.update(DELETE, existing.id());
        } else if (existing == null) {
            create(tx, userId, url);
        } else if (!url.equals(existing.url())) {
            tx.update(UPDATE_URL, Times.now(), url, existing.id());
        }
    }

    /// Any url is kept, "" included.
    public static void create(Tx tx, long userId, String url) {
        Instant now = Times.now();
        tx.insert(INSERT, now, now, url, userId);
    }
}
