package campfire.users;

import campfire.db.Conn;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// A row of `users`. `role` is `member`/`administrator`/`bot`, `status` `active`/`deactivated`/`banned`.
public record User(long id, String name, String emailAddress, String passwordDigest, int role, int status, String bio,
                   String botToken, Instant createdAt, Instant updatedAt) {
    public static final int MEMBER = 0, ADMINISTRATOR = 1, BOT = 2;
    public static final int ACTIVE = 0, DEACTIVATED = 1, BANNED = 2;

    public static final String COLUMNS =
        "\"users\".\"id\", \"users\".\"name\", \"users\".\"email_address\", \"users\".\"password_digest\", \"users\".\"role\", "
            + "\"users\".\"status\", \"users\".\"bio\", \"users\".\"bot_token\", \"users\".\"created_at\", \"users\".\"updated_at\"";
    public static final String SELECT = "SELECT " + COLUMNS + " FROM \"users\"";
    public static final int COLUMN_COUNT = 10;

    public static User from(ResultSet rs) throws SQLException {
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

    public static User find(Conn conn, long id) {
        return conn.one(SELECT + " WHERE \"users\".\"id\" = ? LIMIT 1", User::from, id);
    }

    public static List<User> findAll(Conn conn, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return conn.list(SELECT + " WHERE \"users\".\"id\" IN (" + Conn.placeholders(ids.size()) + ")", User::from, ids.toArray());
    }

    public static User findActiveByEmail(Conn conn, String email) {
        return conn.one(SELECT + " WHERE \"users\".\"status\" = 0 AND \"users\".\"email_address\" = ? LIMIT 1", User::from, email);
    }

    public static User findByEmail(Conn conn, String email) {
        return conn.one(SELECT + " WHERE \"users\".\"email_address\" = ? LIMIT 1", User::from, email);
    }

    public static boolean none(Conn conn) {
        return !conn.exists("SELECT 1 FROM \"users\" LIMIT 1");
    }

    public static User firstAdministrator(Conn conn) {
        return conn.one(SELECT + " WHERE \"users\".\"role\" = 1 ORDER BY \"users\".\"id\" ASC LIMIT 1", User::from);
    }

    /// `User.active.ordered`
    public static List<User> activeOrdered(Conn conn) {
        return conn.list(SELECT + " WHERE \"users\".\"status\" = 0 ORDER BY LOWER(name)", User::from);
    }

    /// `User.active.ordered.without_bots`
    public static List<User> activeOrderedWithoutBots(Conn conn) {
        return conn.list(SELECT + " WHERE \"users\".\"status\" = 0 AND \"users\".\"role\" != 2 ORDER BY LOWER(name)", User::from);
    }

    /// `User.where(status: [active, banned]).ordered.without_bots`
    public static List<User> activeOrBannedOrderedWithoutBots(Conn conn) {
        return conn.list(SELECT + " WHERE \"users\".\"status\" IN (0, 2) AND \"users\".\"role\" != 2 ORDER BY LOWER(name)", User::from);
    }

    /// `User.active_bots.ordered`
    public static List<User> activeBotsOrdered(Conn conn) {
        return conn.list(SELECT + " WHERE \"users\".\"status\" = 0 AND \"users\".\"role\" = 2 ORDER BY LOWER(name)", User::from);
    }

    public static User findActiveBot(Conn conn, long id) {
        return conn.one(SELECT + " WHERE \"users\".\"status\" = 0 AND \"users\".\"role\" = 2 AND \"users\".\"id\" = ? LIMIT 1", User::from, id);
    }

    public static User findActive(Conn conn, long id) {
        return conn.one(SELECT + " WHERE \"users\".\"status\" = 0 AND \"users\".\"id\" = ? LIMIT 1", User::from, id);
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
        return conn.one(SELECT + " WHERE \"users\".\"status\" = 0 AND \"users\".\"role\" = 2 AND \"users\".\"id\" = ? AND \"users\".\"bot_token\" = ? LIMIT 1",
            User::from, id, parts[1]);
    }

    /// Active users not in `excluded`, oldest first (direct-room placeholders).
    public static List<User> activeExcluding(Conn conn, List<Long> excluded, int limit) {
        if (limit <= 0) return List.of();
        var args = new ArrayList<Object>(excluded);
        args.add(limit);
        return conn.list(SELECT + " WHERE \"users\".\"status\" = 0 AND \"users\".\"id\" NOT IN (" + Conn.placeholders(excluded.size())
            + ") ORDER BY \"users\".\"created_at\" ASC LIMIT ?", User::from, args.toArray());
    }

    /// Users in a room, unordered: pages list them as SQLite returns the join, which is not
    /// membership order.
    public static List<User> inRoom(Conn conn, long roomId) {
        return conn.list(SELECT + " INNER JOIN \"memberships\" ON \"users\".\"id\" = \"memberships\".\"user_id\" WHERE \"memberships\".\"room_id\" = ?",
            User::from, roomId);
    }

    /// The room's members among `ids`, in no particular order.
    public static List<User> inRoomAmong(Conn conn, long roomId, List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        args.add(roomId);
        args.addAll(ids);
        return conn.list(SELECT + " INNER JOIN \"memberships\" ON \"users\".\"id\" = \"memberships\".\"user_id\" WHERE \"memberships\".\"room_id\" = ?"
            + " AND \"users\".\"id\" IN (" + Conn.placeholders(ids.size()) + ")", User::from, args.toArray());
    }

    /// Active bots who are members of the room.
    public static List<User> activeBotsInRoom(Conn conn, long roomId) {
        return conn.list(SELECT + " INNER JOIN \"memberships\" ON \"users\".\"id\" = \"memberships\".\"user_id\" WHERE \"memberships\".\"room_id\" = ?"
            + " AND \"users\".\"status\" = 0 AND \"users\".\"role\" = 2", User::from, roomId);
    }

    /// Autocomplete: active users, optionally in a room, whose name contains `query`.
    public static List<User> autocompletable(Conn conn, Long roomId, String query, int limit, int offset) {
        var sql = new StringBuilder(SELECT);
        var args = new ArrayList<Object>();
        if (roomId != null) {
            sql.append(" INNER JOIN \"memberships\" ON \"users\".\"id\" = \"memberships\".\"user_id\" WHERE \"memberships\".\"room_id\" = ? AND");
            args.add(roomId);
        } else {
            sql.append(" WHERE");
        }
        sql.append(" \"users\".\"status\" = 0");
        if (query != null) {
            sql.append(" AND (name like ?)");
            args.add("%" + query + "%");
        }
        sql.append(" ORDER BY LOWER(name) LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return conn.list(sql.toString(), User::from, args.toArray());
    }
}
