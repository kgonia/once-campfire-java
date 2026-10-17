package campfire.users;

import static campfire.db.schema.Tables.MEMBERSHIPS;
import static campfire.db.schema.Tables.ROOMS;
import static campfire.db.schema.Tables.SEARCHES;
import static campfire.db.schema.Tables.USERS;
import static org.jooq.impl.DSL.deleteFrom;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.insertInto;
import static org.jooq.impl.DSL.param;
import static org.jooq.impl.DSL.select;

import campfire.bots.Webhook;
import campfire.push.PushSubscription;
import campfire.rooms.Room;
import campfire.sessions.Session;
import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;
import campfire.db.Database.Tx;
import campfire.db.Sql;
import campfire.db.Times;
import campfire.rails.SecureRandoms;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jooq.Field;
import org.jooq.impl.DSL;

/// User writes: sign-ups, profiles, bots, deactivation and bans.
public final class Users {
    private Users() {}

    private static final String INSERT = Sql.render(insertInto(USERS, USERS.BOT_TOKEN, USERS.CREATED_AT, USERS.EMAIL_ADDRESS, USERS.NAME,
            USERS.PASSWORD_DIGEST, USERS.ROLE, USERS.STATUS, USERS.UPDATED_AT)
        .values(param(USERS.BOT_TOKEN), param(USERS.CREATED_AT), param(USERS.EMAIL_ADDRESS), param(USERS.NAME), param(USERS.PASSWORD_DIGEST),
            param(USERS.ROLE), inline(User.ACTIVE), param(USERS.UPDATED_AT)));
    private static final String JOIN_ROOM = Sql.render(insertInto(MEMBERSHIPS, MEMBERSHIPS.ROOM_ID, MEMBERSHIPS.USER_ID, MEMBERSHIPS.CREATED_AT,
            MEMBERSHIPS.UPDATED_AT)
        .values(param(MEMBERSHIPS.ROOM_ID), param(MEMBERSHIPS.USER_ID), param(MEMBERSHIPS.CREATED_AT), param(MEMBERSHIPS.UPDATED_AT))
        .onConflictDoNothing());
    /// `update`'s statements, indexed by which of name (1), email address (2), password digest (4)
    /// and bio (8) change; `updated_at` and the id follow.
    private static final String[] UPDATE_CHANGED = IntStream.range(0, 16).mapToObj(changed -> {
        var assignments = new LinkedHashMap<Field<?>, Object>();
        if ((changed & 1) != 0) assignments.put(USERS.NAME, param(USERS.NAME));
        if ((changed & 2) != 0) assignments.put(USERS.EMAIL_ADDRESS, param(USERS.EMAIL_ADDRESS));
        if ((changed & 4) != 0) assignments.put(USERS.PASSWORD_DIGEST, param(USERS.PASSWORD_DIGEST));
        if ((changed & 8) != 0) assignments.put(USERS.BIO, param(USERS.BIO));
        assignments.put(USERS.UPDATED_AT, param(USERS.UPDATED_AT));
        return Sql.render(DSL.update(USERS).set(assignments).where(USERS.ID.eq(param(USERS.ID))));
    }).toArray(String[]::new);
    private static final String UPDATE_ROLE = Sql.render(DSL.update(USERS).set(USERS.ROLE, param(USERS.ROLE)).set(USERS.UPDATED_AT, param(USERS.UPDATED_AT))
        .where(USERS.ID.eq(param(USERS.ID))));
    private static final String SET_STATUS = Sql.render(DSL.update(USERS).set(USERS.STATUS, param(USERS.STATUS))
        .set(USERS.UPDATED_AT, param(USERS.UPDATED_AT)).where(USERS.ID.eq(param(USERS.ID))));
    private static final String LEAVE_SHARED_ROOMS = Sql.render(deleteFrom(MEMBERSHIPS).where(MEMBERSHIPS.USER_ID.eq(param(MEMBERSHIPS.USER_ID)))
        .and(MEMBERSHIPS.ROOM_ID.in(select(ROOMS.ID).from(ROOMS).where(ROOMS.TYPE.ne(inline(Room.DIRECT))))));
    private static final String DELETE_SEARCHES = Sql.render(deleteFrom(SEARCHES).where(SEARCHES.USER_ID.eq(param(SEARCHES.USER_ID))));
    private static final String DEACTIVATE = Sql.render(DSL.update(USERS).set(USERS.STATUS, inline(User.DEACTIVATED))
        .set(USERS.EMAIL_ADDRESS, param(USERS.EMAIL_ADDRESS)).set(USERS.UPDATED_AT, param(USERS.UPDATED_AT)).where(USERS.ID.eq(param(USERS.ID))));
    private static final String RESET_BOT_KEY = Sql.render(DSL.update(USERS).set(USERS.BOT_TOKEN, param(USERS.BOT_TOKEN))
        .set(USERS.UPDATED_AT, param(USERS.UPDATED_AT)).where(USERS.ID.eq(param(USERS.ID))));

    /// `BCrypt::Engine.cost` in production.
    public static volatile int bcryptCost = 12;

    private static final BCrypt.Hasher HASHER = BCrypt.with(BCrypt.Version.VERSION_2A, LongPasswordStrategies.truncate(BCrypt.Version.VERSION_2A));
    private static final BCrypt.Verifyer VERIFYER = BCrypt.verifyer(BCrypt.Version.VERSION_2A, LongPasswordStrategies.truncate(BCrypt.Version.VERSION_2A));
    /// Checked against unknown emails so they take as long as known ones.
    private static final String DUMMY_DIGEST = "$2a$12$kDVPfUd.VKsV9HzG8RHRFu0fyaW7gije1Krza2.A0J5S8XEcOQT4S";

    public static String digest(String password) {
        return HASHER.hashToString(bcryptCost, password.toCharArray());
    }

    public static boolean passwordMatches(String password, String digest) {
        if (password == null || digest == null) return false;
        try {
            return VERIFYER.verify(password.getBytes(StandardCharsets.UTF_8), digest.getBytes(StandardCharsets.UTF_8)).verified;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /// `User.active.authenticate_by(email_address:, password:)`. Runs bcrypt outside any connection.
    public static User authenticate(campfire.db.Database db, String email, String password) {
        if (email == null || password == null || email.isEmpty() || password.isEmpty()) return null;
        User user = db.read(conn -> User.findActiveByEmail(conn, email));
        if (user == null) {
            passwordMatches(password, DUMMY_DIGEST);
            return null;
        }
        return passwordMatches(password, user.passwordDigest()) ? user : null;
    }

    /// `User.create!`: grants membership to every open room after commit.
    public static User create(Tx tx, String name, String email, String passwordDigest, int role, String botToken) {
        Instant now = Times.now();
        long id = tx.insert(INSERT, botToken, now, email, name, passwordDigest, role, now);
        for (long roomId : Room.openIds(tx)) tx.update(JOIN_ROOM, roomId, id, now, now);
        return User.find(tx, id);
    }

    /// Profile fields to set; null leaves a field as it is.
    public record Changes(String name, String emailAddress, String passwordDigest, String bio) {}

    /// Writes only the fields that differ, and nothing at all (not even `updated_at`) when none
    /// do. A new password digest always counts as a change.
    public static void update(Tx tx, User user, Changes changes) {
        int changed = 0;
        var values = new java.util.ArrayList<Object>();
        if (changes.name() != null && !changes.name().equals(user.name())) {
            changed |= 1;
            values.add(changes.name());
        }
        if (changes.emailAddress() != null && !changes.emailAddress().equals(user.emailAddress())) {
            changed |= 2;
            values.add(changes.emailAddress());
        }
        if (changes.passwordDigest() != null) {
            changed |= 4;
            values.add(changes.passwordDigest());
        }
        if (changes.bio() != null && !changes.bio().equals(user.bio())) {
            changed |= 8;
            values.add(changes.bio());
        }
        if (changed == 0) return;
        values.add(Times.now());
        values.add(user.id());
        tx.update(UPDATE_CHANGED[changed], values.toArray());
    }

    public static void updateRole(Tx tx, long id, int role) {
        tx.update(UPDATE_ROLE, role, Times.now(), id);
    }

    public static void setStatus(Tx tx, long id, int status) {
        tx.update(SET_STATUS, status, Times.now(), id);
    }

    /// `user.deactivate`: leaves shared rooms, loses push subscriptions, searches and sessions,
    /// and frees the email address.
    public static void deactivate(Tx tx, User user) {
        tx.update(LEAVE_SHARED_ROOMS, user.id());
        PushSubscription.deleteAllFor(tx, user.id());
        tx.update(DELETE_SEARCHES, user.id());
        Session.deleteAllFor(tx, user.id());
        String email = user.emailAddress() == null ? null : user.emailAddress().replace("@", "-deactivated-" + UUID.randomUUID() + "@");
        tx.update(DEACTIVATE, email, Times.now(), user.id());
    }

    /// Bans every address the user has signed in from and signs them out everywhere. An address
    /// that isn't public fails the whole ban, as the ban's validation does in Rails.
    public static void ban(Tx tx, User user) {
        for (String ip : Session.ipAddressesFor(tx, user.id())) {
            if (!Ban.isPublic(ip)) {
                String reason = Ban.literal(ip) == null ? "is not a valid IP address" : "cannot be a private or internal IP address";
                throw new IllegalStateException("Validation failed: Ip address " + reason);
            }
            Ban.create(tx, user.id(), ip);
        }
        Session.deleteAllFor(tx, user.id());
        if (user.status() != User.BANNED) setStatus(tx, user.id(), User.BANNED);
    }

    public static void unban(Tx tx, User user) {
        Ban.deleteAllFor(tx, user.id());
        if (user.status() != User.ACTIVE) setStatus(tx, user.id(), User.ACTIVE);
    }

    // -- Bots ----------------------------------------------------------------------------------

    public static String generateBotToken() {
        return SecureRandoms.alphanumeric(12);
    }

    /// A webhook is created for any url given, even "".
    public static User createBot(Tx tx, String name, String webhookUrl) {
        var bot = create(tx, name, null, null, User.BOT, generateBotToken());
        if (webhookUrl != null) Webhook.create(tx, bot.id(), webhookUrl);
        return bot;
    }

    /// The webhook first; a blank url removes it. A null name keeps the current one.
    public static void updateBot(Tx tx, User bot, String name, String webhookUrl) {
        Webhook.set(tx, bot.id(), webhookUrl);
        update(tx, bot, new Changes(name, null, null, null));
    }

    public static void resetBotKey(Tx tx, long id) {
        tx.update(RESET_BOT_KEY, generateBotToken(), Times.now(), id);
    }
}
