package campfire.users;

import static campfire.db.schema.Tables.MEMBERSHIPS;
import static campfire.db.schema.Tables.USERS;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import campfire.db.Conn;
import campfire.db.Sql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.SelectJoinStep;

/// A row of `users`. `role` is `member`/`administrator`/`bot`, `status` `active`/`deactivated`/`banned`.
public record User(long id, String name, String emailAddress, String passwordDigest, int role, int status, String bio,
                   String botToken, Instant createdAt, Instant updatedAt) {
    public static final int MEMBER = 0, ADMINISTRATOR = 1, BOT = 2;
    public static final int ACTIVE = 0, DEACTIVATED = 1, BANNED = 2;

    /// The columns {@link #from} reads, in its order.
    public static final Field<?>[] COLUMNS = {USERS.ID, USERS.NAME, USERS.EMAIL_ADDRESS, USERS.PASSWORD_DIGEST, USERS.ROLE, USERS.STATUS, USERS.BIO,
        USERS.BOT_TOKEN, USERS.CREATED_AT, USERS.UPDATED_AT};

    static User from(ResultSet rs) throws SQLException {
        return from(rs, 0);
    }

    public static User from(ResultSet rs, int offset) throws SQLException {
        return new User(rs.getLong(offset + 1), rs.getString(offset + 2), rs.getString(offset + 3), rs.getString(offset + 4),
            rs.getInt(offset + 5), rs.getInt(offset + 6), rs.getString(offset + 7), rs.getString(offset + 8),
            Conn.getTime(rs, offset + 9), Conn.getTime(rs, offset + 10));
    }

    public boolean isAdministrator() {
        return role == ADMINISTRATOR;
    }

    public boolean isBot() {
        return role == BOT;
    }

    public boolean isMember() {
        return role == MEMBER;
    }

    public boolean isActive() {
        return status == ACTIVE;
    }

    public boolean isDeactivated() {
        return status == DEACTIVATED;
    }

    public boolean isBanned() {
        return status == BANNED;
    }

    public String roleName() {
        return switch (role) {
            case ADMINISTRATOR -> "administrator";
            case BOT -> "bot";
            default -> "member";
        };
    }

    /// `can_administer?(record)`: administrators, or the record's creator.
    public boolean canAdminister() {
        return isAdministrator();
    }

    public boolean canAdminister(long creatorId) {
        return isAdministrator() || creatorId == id;
    }

    /// `name.scan(/\b\w/).join`: Ruby's `\w` is ASCII, its `\b` sees Unicode word characters.
    public String initials() {
        var out = new StringBuilder();
        int previous = -1;
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            boolean asciiWord = (c < 128 && Character.isLetterOrDigit(c)) || c == '_';
            boolean boundary = previous < 0 || !(Character.isLetterOrDigit(previous) || previous == '_');
            if (asciiWord && boundary) out.appendCodePoint(c);
            previous = c;
            i += Character.charCount(c);
        }
        return out.toString();
    }

    /// `[ name, bio ].compact_blank.join(" – ")`
    public String title() {
        return bio == null || bio.isBlank() ? name : name + " – " + bio;
    }

    /// The name split on ASCII whitespace, empty parts dropped (Ruby's `split(' ')`).
    public List<String> nameParts() {
        return Arrays.stream(name.split("[ \\t\\n\\x0B\\f\\r]+")).filter(part -> !part.isEmpty()).toList();
    }

    public String firstName() {
        List<String> parts = nameParts();
        return parts.isEmpty() ? "" : parts.getFirst();
    }

    public String botKey() {
        return id + "-" + botToken;
    }

    /// `to_param`, as the avatar color hash uses.
    public String toParam() {
        return Long.toString(id);
    }

    // -- Queries -------------------------------------------------------------------------------

    private static SelectJoinStep<?> users() {
        return select(COLUMNS).from(USERS);
    }

    /// Users joined to their memberships, for conditions on `IN_ROOM`.
    private static SelectJoinStep<?> members() {
        return select(COLUMNS).from(USERS).join(MEMBERSHIPS).on(USERS.ID.eq(MEMBERSHIPS.USER_ID));
    }

    private static final Condition ACTIVE_ONLY = USERS.STATUS.eq(inline(ACTIVE));
    private static final Condition BOTS_ONLY = USERS.ROLE.eq(inline(BOT));
    private static final Condition IN_ROOM = MEMBERSHIPS.ROOM_ID.eq(param(MEMBERSHIPS.ROOM_ID));

    private static final String FIND = Sql.render(users().where(USERS.ID.eq(param(USERS.ID))).limit(inline(1)));
    private static final IntFunction<String> FIND_ALL = Sql.perCount(n -> users().where(USERS.ID.in(Sql.params(USERS.ID, n))));
    private static final String FIND_ACTIVE_BY_EMAIL = Sql.render(users().where(ACTIVE_ONLY).and(USERS.EMAIL_ADDRESS.eq(param(USERS.EMAIL_ADDRESS)))
        .limit(inline(1)));
    private static final String FIND_BY_EMAIL = Sql.render(users().where(USERS.EMAIL_ADDRESS.eq(param(USERS.EMAIL_ADDRESS))).limit(inline(1)));
    private static final String ANY = Sql.render(selectOne().from(USERS).limit(inline(1)));
    private static final String FIRST_ADMINISTRATOR = Sql.render(users().where(USERS.ROLE.eq(inline(ADMINISTRATOR))).orderBy(USERS.ID.asc())
        .limit(inline(1)));
    private static final String ACTIVE_ORDERED = Sql.render(users().where(ACTIVE_ONLY).orderBy(lower(USERS.NAME)));
    private static final String ACTIVE_ORDERED_WITHOUT_BOTS = Sql.render(users().where(ACTIVE_ONLY).and(USERS.ROLE.ne(inline(BOT)))
        .orderBy(lower(USERS.NAME)));
    private static final String ACTIVE_OR_BANNED_ORDERED_WITHOUT_BOTS = Sql.render(users().where(USERS.STATUS.in(inline(ACTIVE), inline(BANNED)))
        .and(USERS.ROLE.ne(inline(BOT))).orderBy(lower(USERS.NAME)));
    private static final String ACTIVE_BOTS_ORDERED = Sql.render(users().where(ACTIVE_ONLY).and(BOTS_ONLY).orderBy(lower(USERS.NAME)));
    private static final String FIND_ACTIVE_BOT = Sql.render(users().where(ACTIVE_ONLY).and(BOTS_ONLY).and(USERS.ID.eq(param(USERS.ID))).limit(inline(1)));
    private static final String FIND_ACTIVE = Sql.render(users().where(ACTIVE_ONLY).and(USERS.ID.eq(param(USERS.ID))).limit(inline(1)));
    private static final String AUTHENTICATE_BOT = Sql.render(users().where(ACTIVE_ONLY).and(BOTS_ONLY).and(USERS.ID.eq(param(USERS.ID)))
        .and(USERS.BOT_TOKEN.eq(param(USERS.BOT_TOKEN))).limit(inline(1)));
    /// Binds the excluded ids, then the limit.
    private static final IntFunction<String> ACTIVE_EXCLUDING = Sql.perCount(n -> users().where(ACTIVE_ONLY)
        .and(USERS.ID.notIn(Sql.params(USERS.ID, n))).orderBy(USERS.CREATED_AT.asc()).limit(param("limit", Integer.class)));
    private static final String IN_ROOM_ALL = Sql.render(members().where(IN_ROOM));
    /// Binds the room id, then the user ids.
    private static final IntFunction<String> IN_ROOM_AMONG = Sql.perCount(n -> members().where(IN_ROOM).and(USERS.ID.in(Sql.params(USERS.ID, n))));
    private static final String ACTIVE_BOTS_IN_ROOM = Sql.render(members().where(IN_ROOM).and(ACTIVE_ONLY).and(BOTS_ONLY));
    /// Indexed by whether a room id (1) and a name pattern (2) are bound, before the limit and offset.
    private static final String[] AUTOCOMPLETABLE = new String[4];

    static {
        for (int variant = 0; variant < AUTOCOMPLETABLE.length; variant++) {
            boolean inRoom = (variant & 1) != 0;
            Condition where = inRoom ? IN_ROOM.and(ACTIVE_ONLY) : ACTIVE_ONLY;
            if ((variant & 2) != 0) where = where.and(USERS.NAME.like(param(USERS.NAME)));
            AUTOCOMPLETABLE[variant] = Sql.render((inRoom ? members() : users()).where(where).orderBy(lower(USERS.NAME))
                .limit(param("limit", Integer.class)).offset(param("offset", Integer.class)));
        }
    }

    public static User find(Conn conn, long id) {
        return conn.one(FIND, User::from, id);
    }

    public static List<User> findAll(Conn conn, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return conn.list(FIND_ALL.apply(ids.size()), User::from, ids.toArray());
    }

    public static User findActiveByEmail(Conn conn, String email) {
        return conn.one(FIND_ACTIVE_BY_EMAIL, User::from, email);
    }

    public static User findByEmail(Conn conn, String email) {
        return conn.one(FIND_BY_EMAIL, User::from, email);
    }

    public static boolean none(Conn conn) {
        return !conn.exists(ANY);
    }

    public static User firstAdministrator(Conn conn) {
        return conn.one(FIRST_ADMINISTRATOR, User::from);
    }

    /// `User.active.ordered`
    public static List<User> activeOrdered(Conn conn) {
        return conn.list(ACTIVE_ORDERED, User::from);
    }

    /// `User.active.ordered.without_bots`
    public static List<User> activeOrderedWithoutBots(Conn conn) {
        return conn.list(ACTIVE_ORDERED_WITHOUT_BOTS, User::from);
    }

    /// `User.where(status: [active, banned]).ordered.without_bots`
    public static List<User> activeOrBannedOrderedWithoutBots(Conn conn) {
        return conn.list(ACTIVE_OR_BANNED_ORDERED_WITHOUT_BOTS, User::from);
    }

    /// `User.active_bots.ordered`
    public static List<User> activeBotsOrdered(Conn conn) {
        return conn.list(ACTIVE_BOTS_ORDERED, User::from);
    }

    public static User findActiveBot(Conn conn, long id) {
        return conn.one(FIND_ACTIVE_BOT, User::from, id);
    }

    public static User findActive(Conn conn, long id) {
        return conn.one(FIND_ACTIVE, User::from, id);
    }

    /// `User.authenticate_bot(bot_key)`: `"<id>-<token>"`.
    public static User authenticateBot(Conn conn, String botKey) {
        String[] parts = botKey.split("-", -1);
        if (parts.length < 2) return null;
        long id;
        try {
            id = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        return conn.one(AUTHENTICATE_BOT, User::from, id, parts[1]);
    }

    /// Active users not in `excluded`, oldest first (direct-room placeholders). An empty
    /// `excluded` finds no one, as the `NOT IN (NULL)` it used to render did.
    public static List<User> activeExcluding(Conn conn, List<Long> excluded, int limit) {
        if (limit <= 0 || excluded.isEmpty()) return List.of();
        var args = new ArrayList<Object>(excluded);
        args.add(limit);
        return conn.list(ACTIVE_EXCLUDING.apply(excluded.size()), User::from, args.toArray());
    }

    /// Users in a room, unordered: pages list them as SQLite returns the join, which is not
    /// membership order.
    public static List<User> inRoom(Conn conn, long roomId) {
        return conn.list(IN_ROOM_ALL, User::from, roomId);
    }

    /// The room's members among `ids`, in no particular order.
    public static List<User> inRoomAmong(Conn conn, long roomId, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        args.add(roomId);
        args.addAll(ids);
        return conn.list(IN_ROOM_AMONG.apply(ids.size()), User::from, args.toArray());
    }

    /// Active bots who are members of the room.
    public static List<User> activeBotsInRoom(Conn conn, long roomId) {
        return conn.list(ACTIVE_BOTS_IN_ROOM, User::from, roomId);
    }

    /// Autocomplete: active users, optionally in a room, whose name contains `query`.
    public static List<User> autocompletable(Conn conn, Long roomId, String query, int limit, int offset) {
        var args = new ArrayList<Object>();
        if (roomId != null) args.add(roomId);
        if (query != null) args.add("%" + query + "%");
        args.add(limit);
        args.add(offset);
        return conn.list(AUTOCOMPLETABLE[(roomId != null ? 1 : 0) | (query != null ? 2 : 0)], User::from, args.toArray());
    }
}
