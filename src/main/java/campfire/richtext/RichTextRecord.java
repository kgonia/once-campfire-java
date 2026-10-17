package campfire.richtext;

import static campfire.db.schema.Tables.ACTION_TEXT_RICH_TEXTS;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.update;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/// A row of `action_text_rich_texts`.
public record RichTextRecord(long id, String recordType, long recordId, String name, String body) {
    private static final String FIND = Sql.render(select(ACTION_TEXT_RICH_TEXTS.ID, ACTION_TEXT_RICH_TEXTS.RECORD_TYPE, ACTION_TEXT_RICH_TEXTS.RECORD_ID,
            ACTION_TEXT_RICH_TEXTS.NAME, ACTION_TEXT_RICH_TEXTS.BODY)
        .from(ACTION_TEXT_RICH_TEXTS).where(ACTION_TEXT_RICH_TEXTS.RECORD_TYPE.eq(param(ACTION_TEXT_RICH_TEXTS.RECORD_TYPE)))
        .and(ACTION_TEXT_RICH_TEXTS.RECORD_ID.eq(param(ACTION_TEXT_RICH_TEXTS.RECORD_ID))).and(ACTION_TEXT_RICH_TEXTS.NAME.eq(param(ACTION_TEXT_RICH_TEXTS.NAME)))
        .limit(inline(1)));
    private static final IntFunction<String> MESSAGE_BODIES = Sql.perCount(n -> select(ACTION_TEXT_RICH_TEXTS.RECORD_ID, ACTION_TEXT_RICH_TEXTS.BODY)
        .from(ACTION_TEXT_RICH_TEXTS).where(ACTION_TEXT_RICH_TEXTS.RECORD_TYPE.eq(inline("Message"))).and(ACTION_TEXT_RICH_TEXTS.NAME.eq(inline("body")))
        .and(ACTION_TEXT_RICH_TEXTS.RECORD_ID.in(Sql.params(ACTION_TEXT_RICH_TEXTS.RECORD_ID, n))));
    private static final String INSERT = Sql.render(insertInto(ACTION_TEXT_RICH_TEXTS, ACTION_TEXT_RICH_TEXTS.BODY, ACTION_TEXT_RICH_TEXTS.CREATED_AT,
            ACTION_TEXT_RICH_TEXTS.NAME, ACTION_TEXT_RICH_TEXTS.RECORD_ID, ACTION_TEXT_RICH_TEXTS.RECORD_TYPE, ACTION_TEXT_RICH_TEXTS.UPDATED_AT)
        .values(param(ACTION_TEXT_RICH_TEXTS.BODY), param(ACTION_TEXT_RICH_TEXTS.CREATED_AT), param(ACTION_TEXT_RICH_TEXTS.NAME),
            param(ACTION_TEXT_RICH_TEXTS.RECORD_ID), param(ACTION_TEXT_RICH_TEXTS.RECORD_TYPE), param(ACTION_TEXT_RICH_TEXTS.UPDATED_AT)));
    private static final String UPDATE_BODY = Sql.render(update(ACTION_TEXT_RICH_TEXTS).set(ACTION_TEXT_RICH_TEXTS.BODY, param(ACTION_TEXT_RICH_TEXTS.BODY))
        .set(ACTION_TEXT_RICH_TEXTS.UPDATED_AT, param(ACTION_TEXT_RICH_TEXTS.UPDATED_AT)).where(ACTION_TEXT_RICH_TEXTS.ID.eq(param(ACTION_TEXT_RICH_TEXTS.ID))));

    public static RichTextRecord find(Conn conn, String recordType, long recordId, String name) {
        return conn.one(FIND, rs -> new RichTextRecord(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)),
            recordType, recordId, name);
    }

    /// Message bodies by message id.
    public static Map<Long, String> messageBodies(Conn conn, List<Long> messageIds) {
        var bodies = new HashMap<Long, String>();
        if (messageIds.isEmpty()) return bodies;
        conn.list(MESSAGE_BODIES.apply(messageIds.size()), rs -> {
            bodies.put(rs.getLong(1), rs.getString(2));
            return null;
        }, messageIds.toArray());
        return bodies;
    }

    public static void create(Tx tx, String recordType, long recordId, String name, String body, Instant now) {
        tx.insert(INSERT, body, now, name, recordId, recordType, now);
    }

    public static void updateBody(Tx tx, long id, String body, Instant now) {
        tx.update(UPDATE_BODY, body, now, id);
    }
}
