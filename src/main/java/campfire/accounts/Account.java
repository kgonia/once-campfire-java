package campfire.accounts;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import campfire.rails.Json;
import campfire.rails.SecureRandoms;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/// The single row of `accounts`.
public record Account(long id, String name, String joinCode, String customStyles, String settings, Instant createdAt, Instant updatedAt) {
    private static final String SELECT = "SELECT \"id\", \"name\", \"join_code\", \"custom_styles\", \"settings\", \"created_at\", \"updated_at\" FROM \"accounts\"";

    /// `Current.account` (`Account.first`).
    public static Account first(Conn conn) {
        return conn.one(SELECT + " ORDER BY \"id\" ASC LIMIT 1", rs -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5), Conn.getTime(rs, 6), Conn.getTime(rs, 7)));
    }

    public static boolean any(Conn conn) {
        return conn.exists("SELECT 1 FROM \"accounts\" LIMIT 1");
    }

    static final String RESTRICT_ROOM_CREATION = "restrict_room_creation_to_administrators";

    /// Whether only administrators may create rooms: set to anything but blank or false.
    public boolean restrictRoomCreationToAdministrators() {
        return switch (settingsMap().get(RESTRICT_ROOM_CREATION)) {
            case null -> false;
            case Boolean b -> b;
            case String text -> !text.isBlank();
            case java.util.Collection<?> list -> !list.isEmpty();
            case Map<?, ?> map -> !map.isEmpty();
            default -> true;
        };
    }

    /// The stored settings with the defaults filled in.
    public Map<String, Object> settingsMap() {
        Object parsed = settings == null ? null : Json.parseOrNull(settings);
        if (parsed instanceof String nested) parsed = Json.parseOrNull(nested);
        @SuppressWarnings("unchecked")
        var map = parsed instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<String, Object>();
        map.putIfAbsent(RESTRICT_ROOM_CREATION, false);
        return map;
    }

    /// The settings JSON after assigning form values (cast as booleans: blank is null, "0", "f",
    /// "false", "off" in either case are false, anything else true), or null when nothing would
    /// change. Keys outside the schema fail, as in Rails.
    public String settingsJsonWith(Map<String, String> values) {
        Map<String, Object> original = settingsMap();
        var updated = new TreeMap<>(original);
        for (var entry : values.entrySet()) {
            if (!entry.getKey().equals(RESTRICT_ROOM_CREATION)) {
                throw new IllegalStateException("undefined method '" + entry.getKey() + "=' for account settings");
            }
            String value = entry.getValue();
            updated.put(RESTRICT_ROOM_CREATION, value.isEmpty() ? null : !List.of("0", "f", "F", "false", "FALSE", "off", "OFF").contains(value));
        }
        return settings == null || !updated.equals(new TreeMap<>(original)) ? Json.generate(updated) : null;
    }

    /// `SecureRandom.alphanumeric(12).scan(/.{4}/).join("-")`
    public static String generateJoinCode() {
        String code = SecureRandoms.alphanumeric(12);
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12);
    }

    public static long create(Tx tx, String name) {
        Instant now = Times.now();
        return tx.insert("INSERT INTO \"accounts\" (\"created_at\", \"join_code\", \"name\", \"updated_at\") VALUES (?, ?, ?, ?)",
            now, generateJoinCode(), name, now);
    }

    /// Writes only what changed (and `updated_at` then); a null argument leaves its column alone,
    /// except `customStyles` when `stylesGiven`.
    public static void update(Tx tx, Account account, String name, boolean stylesGiven, String customStyles, String settingsJson) {
        var columns = new ArrayList<String>();
        var values = new ArrayList<Object>();
        if (name != null && !name.equals(account.name())) {
            columns.add("name");
            values.add(name);
        }
        if (stylesGiven && !Objects.equals(customStyles, account.customStyles())) {
            columns.add("custom_styles");
            values.add(customStyles);
        }
        if (settingsJson != null) {
            columns.add("settings");
            values.add(settingsJson);
        }
        if (columns.isEmpty()) return;
        columns.add("updated_at");
        values.add(Times.now());
        values.add(account.id());
        var assignments = new StringJoiner(", ");
        for (String column : columns) assignments.add("\"" + column + "\" = ?");
        tx.update("UPDATE \"accounts\" SET " + assignments + " WHERE \"accounts\".\"id\" = ?", values.toArray());
    }

    public static void touch(Tx tx, long id) {
        tx.update("UPDATE \"accounts\" SET \"updated_at\" = ? WHERE \"id\" = ?", Times.now(), id);
    }

    public static void resetJoinCode(Tx tx, long id) {
        tx.update("UPDATE \"accounts\" SET \"join_code\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", generateJoinCode(), Times.now(), id);
    }
}
