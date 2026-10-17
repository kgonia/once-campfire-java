package campfire.accounts;

import static campfire.db.schema.Tables.ACCOUNTS;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import campfire.rails.Json;
import campfire.rails.SecureRandoms;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.jooq.Field;
import org.jooq.impl.DSL;

/// The single row of `accounts`.
public record Account(long id, String name, String joinCode, String customStyles, String settings, Instant createdAt, Instant updatedAt) {
    private static final String FIRST = Sql.render(select(ACCOUNTS.ID, ACCOUNTS.NAME, ACCOUNTS.JOIN_CODE, ACCOUNTS.CUSTOM_STYLES, ACCOUNTS.SETTINGS,
            ACCOUNTS.CREATED_AT, ACCOUNTS.UPDATED_AT)
        .from(ACCOUNTS).orderBy(ACCOUNTS.ID.asc()).limit(inline(1)));
    private static final String ANY = Sql.render(selectOne().from(ACCOUNTS).limit(inline(1)));
    private static final String INSERT = Sql.render(insertInto(ACCOUNTS, ACCOUNTS.CREATED_AT, ACCOUNTS.JOIN_CODE, ACCOUNTS.NAME, ACCOUNTS.UPDATED_AT)
        .values(param(ACCOUNTS.CREATED_AT), param(ACCOUNTS.JOIN_CODE), param(ACCOUNTS.NAME), param(ACCOUNTS.UPDATED_AT)));
    /// The settings bound as the text Rails stores: a JSON-typed bind renders `json(?)`, which
    /// would make SQLite reformat it.
    private static final Field<String> SETTINGS_TEXT = ACCOUNTS.SETTINGS.coerce(String.class);
    /// `update`'s statements, indexed by which of name (1), custom styles (2) and settings (4)
    /// change; `updated_at` and the id follow.
    private static final String[] UPDATE_CHANGED = IntStream.range(0, 8).mapToObj(changed -> {
        var assignments = new LinkedHashMap<Field<?>, Object>();
        if ((changed & 1) != 0) assignments.put(ACCOUNTS.NAME, param(ACCOUNTS.NAME));
        if ((changed & 2) != 0) assignments.put(ACCOUNTS.CUSTOM_STYLES, param(ACCOUNTS.CUSTOM_STYLES));
        if ((changed & 4) != 0) assignments.put(SETTINGS_TEXT, param(SETTINGS_TEXT));
        assignments.put(ACCOUNTS.UPDATED_AT, param(ACCOUNTS.UPDATED_AT));
        return Sql.render(DSL.update(ACCOUNTS).set(assignments).where(ACCOUNTS.ID.eq(param(ACCOUNTS.ID))));
    }).toArray(String[]::new);
    private static final String RESET_JOIN_CODE = Sql.render(DSL.update(ACCOUNTS).set(ACCOUNTS.JOIN_CODE, param(ACCOUNTS.JOIN_CODE))
        .set(ACCOUNTS.UPDATED_AT, param(ACCOUNTS.UPDATED_AT)).where(ACCOUNTS.ID.eq(param(ACCOUNTS.ID))));

    /// `Current.account` (`Account.first`).
    public static Account first(Conn conn) {
        return conn.one(FIRST, rs -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3),
            rs.getString(4), rs.getString(5), Conn.getTime(rs, 6), Conn.getTime(rs, 7)));
    }

    public static boolean any(Conn conn) {
        return conn.exists(ANY);
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
        return tx.insert(INSERT, now, generateJoinCode(), name, now);
    }

    /// Writes only what changed (and `updated_at` then); a null argument leaves its column alone,
    /// except `customStyles` when `stylesGiven`.
    public static void update(Tx tx, Account account, String name, boolean stylesGiven, String customStyles, String settingsJson) {
        int changed = 0;
        var values = new ArrayList<Object>();
        if (name != null && !name.equals(account.name())) {
            changed |= 1;
            values.add(name);
        }
        if (stylesGiven && !Objects.equals(customStyles, account.customStyles())) {
            changed |= 2;
            values.add(customStyles);
        }
        if (settingsJson != null) {
            changed |= 4;
            values.add(settingsJson);
        }
        if (changed == 0) return;
        values.add(Times.now());
        values.add(account.id());
        tx.update(UPDATE_CHANGED[changed], values.toArray());
    }

    public static void resetJoinCode(Tx tx, long id) {
        tx.update(RESET_JOIN_CODE, generateJoinCode(), Times.now(), id);
    }
}
