package campfire.messages;

import static campfire.db.schema.Tables.ACTION_TEXT_RICH_TEXTS;
import static campfire.db.schema.Tables.BOOSTS;
import static campfire.db.schema.Tables.MEMBERSHIPS;
import static campfire.db.schema.Tables.MESSAGES;
import static campfire.db.schema.Tables.ROOMS;
import static org.jooq.impl.DSL.condition;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.update;

import campfire.richtext.RichTextRecord;
import campfire.rooms.Membership;
import campfire.rooms.Room;
import campfire.storage.Attachment;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.jooq.Field;
import org.jooq.SelectConditionStep;
import org.jooq.Table;

/// A row of `messages`. Its body is an Action Text record and its attachment an Active Storage one.
public record Message(long id, long roomId, long creatorId, String clientMessageId, Instant createdAt, Instant updatedAt) {
    public static final int PAGE_SIZE = 40;
    public static final String RECORD_TYPE = "Message";

    /// The columns {@link #from} reads, in its order.
    static final Field<?>[] COLUMNS = {MESSAGES.ID, MESSAGES.ROOM_ID, MESSAGES.CREATOR_ID, MESSAGES.CLIENT_MESSAGE_ID, MESSAGES.CREATED_AT,
        MESSAGES.UPDATED_AT};

    public static Message from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    public static Message from(ResultSet rs, int offset) throws SQLException {
        return new Message(rs.getLong(offset + 1), rs.getLong(offset + 2), rs.getLong(offset + 3), rs.getString(offset + 4),
            Conn.getTime(rs, offset + 5), Conn.getTime(rs, offset + 6));
    }

    /// `to_key`: messages are identified in the DOM by their client id (`message_<client id>`).
    public String domId() {
        return "message_" + clientMessageId;
    }

    public String domId(String prefix) {
        return prefix + "_message_" + clientMessageId;
    }

    // -- Queries -------------------------------------------------------------------------------

    /// `room.messages`; binds the room id.
    private static SelectConditionStep<?> inRoom() {
        return select(COLUMNS).from(MESSAGES).where(MESSAGES.ROOM_ID.eq(param(MESSAGES.ROOM_ID)));
    }

    /// `user.reachable_messages`; binds the user id.
    private static SelectConditionStep<?> reachable() {
        return select(COLUMNS).from(MESSAGES).join(ROOMS).on(MESSAGES.ROOM_ID.eq(ROOMS.ID)).join(MEMBERSHIPS).on(ROOMS.ID.eq(MEMBERSHIPS.ROOM_ID))
            .where(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID)));
    }

    private static final String FIND = Sql.render(select(COLUMNS).from(MESSAGES).where(MESSAGES.ID.eq(param(MESSAGES.ID))).limit(inline(1)));
    private static final String FIND_IN_ROOM = Sql.render(inRoom().and(MESSAGES.ID.eq(param(MESSAGES.ID))).limit(inline(1)));
    private static final String FIND_REACHABLE = Sql.render(reachable().and(MESSAGES.ID.eq(param(MESSAGES.ID))).limit(inline(1)));
    private static final String FOR_ROOM = Sql.render(inRoom());
    private static final String BY_CREATOR = Sql.render(select(COLUMNS).from(MESSAGES).where(MESSAGES.CREATOR_ID.eq(param(MESSAGES.CREATOR_ID))));
    private static final String COUNT_IN_ROOM = Sql.render(select(count()).from(MESSAGES).where(MESSAGES.ROOM_ID.eq(param(MESSAGES.ROOM_ID))));
    private static final String PAGED = Sql.render(selectOne().from(MESSAGES).where(MESSAGES.ROOM_ID.eq(param(MESSAGES.ROOM_ID)))
        .limit(inline(1)).offset(inline(PAGE_SIZE)));
    private static final String LAST_PAGE = Sql.render(inRoom().orderBy(MESSAGES.CREATED_AT.desc()).limit(inline(PAGE_SIZE)));
    private static final String PAGE_BEFORE = Sql.render(inRoom().and(MESSAGES.CREATED_AT.lt(param(MESSAGES.CREATED_AT)))
        .orderBy(MESSAGES.CREATED_AT.desc()).limit(inline(PAGE_SIZE)));
    private static final String PAGE_AFTER = Sql.render(inRoom().and(MESSAGES.CREATED_AT.gt(param(MESSAGES.CREATED_AT)))
        .orderBy(MESSAGES.CREATED_AT.asc()).limit(inline(PAGE_SIZE)));
    /// `+created_at`, kept from the hand-written query: a unary plus stops SQLite from using an index
    /// for the sort. The schema has no index on created_at, so today the plan is the same either way.
    private static final Field<Instant> UNINDEXED_CREATED_AT = field("+{0}", MESSAGES.CREATED_AT.getDataType(), MESSAGES.CREATED_AT);
    private static final String PAGE_UPDATED_SINCE = Sql.render(inRoom().and(MESSAGES.UPDATED_AT.gt(param(MESSAGES.UPDATED_AT)))
        .orderBy(UNINDEXED_CREATED_AT.desc()).limit(inline(PAGE_SIZE)));
    /// Binds the room, the excluded ids and the time.
    private static final IntFunction<String> PAGE_UPDATED_SINCE_EXCLUDING = Sql.perCount(n -> inRoom()
        .and(MESSAGES.ID.notIn(Sql.params(MESSAGES.ID, n))).and(MESSAGES.UPDATED_AT.gt(param(MESSAGES.UPDATED_AT)))
        .orderBy(UNINDEXED_CREATED_AT.desc()).limit(inline(PAGE_SIZE)));
    private static final String EXISTS_BEFORE = Sql.render(selectOne().from(MESSAGES).where(MESSAGES.ROOM_ID.eq(param(MESSAGES.ROOM_ID)))
        .and(MESSAGES.CREATED_AT.lt(param(MESSAGES.CREATED_AT))).limit(inline(1)));
    private static final String EXISTS_AFTER = Sql.render(selectOne().from(MESSAGES).where(MESSAGES.ROOM_ID.eq(param(MESSAGES.ROOM_ID)))
        .and(MESSAGES.CREATED_AT.gt(param(MESSAGES.CREATED_AT))).limit(inline(1)));

    public static Message find(Conn conn, long id) {
        return conn.one(FIND, Message::from, id);
    }

    public static Message findInRoom(Conn conn, long roomId, long id) {
        return conn.one(FIND_IN_ROOM, Message::from, roomId, id);
    }

    /// `user.reachable_messages.find(id)`
    public static Message findReachable(Conn conn, long userId, long id) {
        return conn.one(FIND_REACHABLE, Message::from, userId, id);
    }

    public static List<Message> forRoom(Conn conn, long roomId) {
        return conn.list(FOR_ROOM, Message::from, roomId);
    }

    public static List<Message> byCreator(Conn conn, long creatorId) {
        return conn.list(BY_CREATOR, Message::from, creatorId);
    }

    public static long countInRoom(Conn conn, long roomId) {
        return conn.count(COUNT_IN_ROOM, roomId);
    }

    /// `messages.paged?`: more than a page.
    public static boolean paged(Conn conn, long roomId) {
        return conn.exists(PAGED, roomId);
    }

    /// `last_page`: the newest page, oldest first.
    public static List<Message> lastPage(Conn conn, long roomId) {
        return reversed(conn.list(LAST_PAGE, Message::from, roomId));
    }

    public static List<Message> pageBefore(Conn conn, long roomId, Message message) {
        return reversed(conn.list(PAGE_BEFORE, Message::from, roomId, message.createdAt()));
    }

    public static List<Message> pageAfter(Conn conn, long roomId, Message message) {
        return conn.list(PAGE_AFTER, Message::from, roomId, message.createdAt());
    }

    public static List<Message> pageAround(Conn conn, long roomId, Message message) {
        var page = new ArrayList<>(pageBefore(conn, roomId, message));
        page.add(message);
        page.addAll(pageAfter(conn, roomId, message));
        return page;
    }

    public static List<Message> pageCreatedSince(Conn conn, long roomId, Instant time) {
        return conn.list(PAGE_AFTER, Message::from, roomId, time);
    }

    public static List<Message> pageUpdatedSince(Conn conn, long roomId, Instant time, List<Long> excluding) {
        if (excluding.isEmpty()) return reversed(conn.list(PAGE_UPDATED_SINCE, Message::from, roomId, time));
        var args = new ArrayList<Object>();
        args.add(roomId);
        args.addAll(excluding);
        args.add(time);
        return reversed(conn.list(PAGE_UPDATED_SINCE_EXCLUDING.apply(excluding.size()), Message::from, args.toArray()));
    }

    public static boolean existsBefore(Conn conn, long roomId, Message message) {
        return conn.exists(EXISTS_BEFORE, roomId, message.createdAt());
    }

    public static boolean existsAfter(Conn conn, long roomId, Message message) {
        return conn.exists(EXISTS_AFTER, roomId, message.createdAt());
    }

    private static final int SEARCH_PROBE_LIMIT = 1000;
    /// The FTS5 index, a virtual table the code generator skips, so it's named by hand.
    private static final Table<?> INDEX = table(name("message_search_index")).as("idx");
    private static final Field<Long> INDEX_ROWID = field(name("idx", "rowid"), Long.class);
    private static final Field<String> INDEX_BODY = field(name("idx", "body"), String.class);
    /// Binds the user and the match terms; the last column says whether the user can see the message.
    private static final String SEARCH_PROBE = Sql.render(
        select(Stream.concat(Stream.of(COLUMNS), Stream.of(MEMBERSHIPS.USER_ID.isNotNull().as("reachable"))).toList())
            .from(MESSAGES).join(ROOMS).on(MESSAGES.ROOM_ID.eq(ROOMS.ID))
            .leftJoin(MEMBERSHIPS).on(ROOMS.ID.eq(MEMBERSHIPS.ROOM_ID)).and(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID)))
            .join(INDEX).on(MESSAGES.ID.eq(INDEX_ROWID))
            .where(condition("{0} MATCH {1}", INDEX_BODY, param("terms", String.class)))
            .orderBy(INDEX_ROWID.desc()).limit(inline(SEARCH_PROBE_LIMIT)));
    private static final String SEARCH_REACHABLE = Sql.render(select(COLUMNS).from(MESSAGES).join(ROOMS).on(MESSAGES.ROOM_ID.eq(ROOMS.ID))
        .join(MEMBERSHIPS).on(ROOMS.ID.eq(MEMBERSHIPS.ROOM_ID)).join(INDEX).on(MESSAGES.ID.eq(INDEX_ROWID))
        .where(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID))).and(condition("{0} MATCH {1}", INDEX_BODY, param("terms", String.class)))
        .orderBy(MESSAGES.ID.desc()).limit(inline(100)));

    /// `user.reachable_messages.search(query).last(100)`: the newest hundred matches by id. Words are
    /// literal FTS terms. A bounded scan of the newest matches answers most searches; when most of
    /// them are in rooms the user can't see, a membership-scoped query does.
    public static List<Message> searchReachable(Conn conn, long userId, String query) {
        String terms = matchTerms(query);
        if (terms.isEmpty()) return List.of();
        var found = new ArrayList<Message>();
        var rows = conn.list(SEARCH_PROBE, rs -> rs.getBoolean(COLUMNS.length + 1) ? from(rs) : null, userId, terms);
        int examined = rows.size();
        for (var message : rows) {
            if (message == null) continue;
            found.add(message);
            if (found.size() == 100) return reversed(found);
        }
        if (examined < SEARCH_PROBE_LIMIT) return reversed(found);
        return reversed(conn.list(SEARCH_REACHABLE, Message::from, userId, terms));
    }

    /// Each whitespace-separated word as a quoted FTS5 string, so `NOT`, `OR` and `*` are literal.
    static String matchTerms(String query) {
        var out = new StringBuilder();
        for (String word : query.split("[\\s\\x00]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append('"').append(word.replace("\"", "\"\"")).append('"');
        }
        return out.toString();
    }

    private static <T> List<T> reversed(List<T> list) {
        Collections.reverse(list);
        return list;
    }

    // -- Writes --------------------------------------------------------------------------------

    private static final String INSERT = Sql.render(insertInto(MESSAGES, MESSAGES.CLIENT_MESSAGE_ID, MESSAGES.CREATED_AT, MESSAGES.CREATOR_ID,
            MESSAGES.ROOM_ID, MESSAGES.UPDATED_AT)
        .values(param(MESSAGES.CLIENT_MESSAGE_ID), param(MESSAGES.CREATED_AT), param(MESSAGES.CREATOR_ID), param(MESSAGES.ROOM_ID),
            param(MESSAGES.UPDATED_AT)));
    private static final String TOUCH = Sql.render(update(MESSAGES).set(MESSAGES.UPDATED_AT, param(MESSAGES.UPDATED_AT))
        .where(MESSAGES.ID.eq(param(MESSAGES.ID))));
    private static final String DELETE_BOOSTS = Sql.render(deleteFrom(BOOSTS).where(BOOSTS.MESSAGE_ID.eq(param(BOOSTS.MESSAGE_ID))));
    private static final String DELETE_RICH_TEXTS = Sql.render(deleteFrom(ACTION_TEXT_RICH_TEXTS)
        .where(ACTION_TEXT_RICH_TEXTS.RECORD_TYPE.eq(inline(RECORD_TYPE))).and(ACTION_TEXT_RICH_TEXTS.RECORD_ID.eq(param(ACTION_TEXT_RICH_TEXTS.RECORD_ID))));
    private static final String DELETE = Sql.render(deleteFrom(MESSAGES).where(MESSAGES.ID.eq(param(MESSAGES.ID))));
    // The index's own statements stay plain SQL: it's only rowid and body.
    private static final String INDEX_INSERT = "insert into message_search_index(rowid, body) values (?, ?)";
    private static final String INDEX_UPDATE = "update message_search_index set body = ? where rowid = ?";
    private static final String INDEX_DELETE = "delete from message_search_index where rowid = ?";

    /// Creates the message with its body or attachment, indexes it for search and marks the room
    /// unread for everyone else, in one transaction (as current Rails does).
    public static Message create(Tx tx, long roomId, long creatorId, String clientMessageId, String bodyHtml, Long attachmentBlobId,
                                 String plainText) {
        Instant now = Times.now();
        String clientId = clientMessageId == null || clientMessageId.isBlank() ? UUID.randomUUID().toString() : clientMessageId;
        long id = tx.insert(INSERT, clientId, now, creatorId, roomId, now);
        // Each record gets its own clock reading, and saving the body or attachment touches the
        // message, so `updated_at` comes out just after `created_at`, as in Rails.
        Instant updatedAt = now;
        if (bodyHtml != null) RichTextRecord.create(tx, RECORD_TYPE, id, "body", bodyHtml, Times.now());
        if (attachmentBlobId != null) Attachment.create(tx, RECORD_TYPE, id, "attachment", attachmentBlobId, Times.now());
        if (bodyHtml != null || attachmentBlobId != null) {
            updatedAt = Times.now();
            tx.update(TOUCH, updatedAt, id);
        }
        Room.touch(tx, roomId);
        tx.update(INDEX_INSERT, id, plainText);
        Membership.markUnread(tx, roomId, creatorId, now);
        return new Message(id, roomId, creatorId, clientId, now, updatedAt);
    }

    /// Replaces the body (if it changed), touches the message and room and reindexes it.
    public static Message updateBody(Tx tx, Message message, String bodyHtml, String plainText) {
        var record = RichTextRecord.find(tx, RECORD_TYPE, message.id(), "body");
        if (record != null && bodyHtml.equals(record.body())) return message;
        Instant now = Times.now();
        if (record == null) RichTextRecord.create(tx, RECORD_TYPE, message.id(), "body", bodyHtml, now);
        else RichTextRecord.updateBody(tx, record.id(), bodyHtml, now);
        return touch(tx, message, plainText);
    }

    /// `message.touch` (a boost or body change), reindexing with `plainText` when given.
    public static Message touch(Tx tx, Message message, String plainText) {
        Instant now = Times.now();
        tx.update(TOUCH, now, message.id());
        Room.touch(tx, message.roomId());
        if (plainText != null) tx.update(INDEX_UPDATE, plainText, message.id());
        return new Message(message.id(), message.roomId(), message.creatorId(), message.clientMessageId(), message.createdAt(), now);
    }

    /// `message.destroy`: boosts, body, attachment and index entry go with it. Returns the blob to purge.
    public static Long destroy(Tx tx, Message message, boolean touchRoom) {
        Long blobId = null;
        var attachment = Attachment.find(tx, RECORD_TYPE, message.id(), "attachment");
        if (attachment != null) {
            Attachment.delete(tx, attachment.id());
            blobId = attachment.blobId();
        }
        tx.update(DELETE_BOOSTS, message.id());
        tx.update(DELETE_RICH_TEXTS, message.id());
        tx.update(DELETE, message.id());
        tx.update(INDEX_DELETE, message.id());
        if (touchRoom) Room.touch(tx, message.roomId());
        return blobId;
    }
}
