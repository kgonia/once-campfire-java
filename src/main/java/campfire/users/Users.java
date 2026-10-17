package campfire.users;

import campfire.bots.Webhook;
import campfire.push.PushSubscription;
import campfire.rooms.Room;
import campfire.sessions.Session;
import at.favre.lib.crypto.bcrypt.BCrypt;
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies;
import campfire.db.Conn;
import campfire.db.Database.Tx;
import campfire.db.Times;
import campfire.rails.SecureRandoms;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/// User writes: sign-ups, profiles, bots, deactivation and bans.
public final class Users {
    private Users() {}

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
        long id = tx.insert("INSERT INTO \"users\" (\"bot_token\", \"created_at\", \"email_address\", \"name\", \"password_digest\", \"role\", \"status\", \"updated_at\")"
            + " VALUES (?, ?, ?, ?, ?, ?, 0, ?)", botToken, now, email, name, passwordDigest, role, now);
        for (long roomId : Room.openIds(tx)) {
            tx.update("INSERT INTO \"memberships\" (\"room_id\", \"user_id\", \"created_at\", \"updated_at\") VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                roomId, id, now, now);
        }
        return User.find(tx, id);
    }

    /// `user.update(name:, email_address:, password:, bio:)`; null leaves a field alone.
    public static void updateProfile(Tx tx, long id, String name, String email, String password, String bio) {
        var user = User.find(tx, id);
        tx.update("UPDATE \"users\" SET \"name\" = ?, \"email_address\" = ?, \"password_digest\" = ?, \"bio\" = ?, \"updated_at\" = ? WHERE \"id\" = ?",
            name != null ? name : user.name(),
            email != null ? email : user.emailAddress(),
            password != null && !password.isEmpty() ? digest(password) : user.passwordDigest(),
            bio != null ? bio : user.bio(),
            Times.now(), id);
    }

    /// Profile fields to set; null leaves a field as it is.
    public record Changes(String name, String emailAddress, String passwordDigest, String bio) {}

    /// Writes only the fields that differ, and nothing at all (not even `updated_at`) when none
    /// do. A new password digest always counts as a change.
    public static void update(Tx tx, User user, Changes changes) {
        var columns = new java.util.ArrayList<String>();
        var values = new java.util.ArrayList<Object>();
        if (changes.name() != null && !changes.name().equals(user.name())) {
            columns.add("name");
            values.add(changes.name());
        }
        if (changes.emailAddress() != null && !changes.emailAddress().equals(user.emailAddress())) {
            columns.add("email_address");
            values.add(changes.emailAddress());
        }
        if (changes.passwordDigest() != null) {
            columns.add("password_digest");
            values.add(changes.passwordDigest());
        }
        if (changes.bio() != null && !changes.bio().equals(user.bio())) {
            columns.add("bio");
            values.add(changes.bio());
        }
        if (columns.isEmpty()) return;
        columns.add("updated_at");
        values.add(Times.now());
        values.add(user.id());
        String assignments = String.join(", ", columns.stream().map(column -> "\"" + column + "\" = ?").toList());
        tx.update("UPDATE \"users\" SET " + assignments + " WHERE \"users\".\"id\" = ?", values.toArray());
    }

    public static void updateName(Tx tx, long id, String name) {
        tx.update("UPDATE \"users\" SET \"name\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", name, Times.now(), id);
    }

    public static void updateRole(Tx tx, long id, int role) {
        tx.update("UPDATE \"users\" SET \"role\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", role, Times.now(), id);
    }

    public static void touch(Tx tx, long id) {
        tx.update("UPDATE \"users\" SET \"updated_at\" = ? WHERE \"id\" = ?", Times.now(), id);
    }

    public static void setStatus(Tx tx, long id, int status) {
        tx.update("UPDATE \"users\" SET \"status\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", status, Times.now(), id);
    }

    /// `user.deactivate`: leaves shared rooms, loses push subscriptions, searches and sessions,
    /// and frees the email address.
    public static void deactivate(Tx tx, User user) {
        tx.update("DELETE FROM \"memberships\" WHERE \"user_id\" = ? AND \"room_id\" IN (SELECT \"id\" FROM \"rooms\" WHERE \"type\" != 'Rooms::Direct')", user.id());
        PushSubscription.deleteAllFor(tx, user.id());
        tx.update("DELETE FROM \"searches\" WHERE \"user_id\" = ?", user.id());
        Session.deleteAllFor(tx, user.id());
        String email = user.emailAddress() == null ? null : user.emailAddress().replace("@", "-deactivated-" + UUID.randomUUID() + "@");
        tx.update("UPDATE \"users\" SET \"status\" = 1, \"email_address\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", email, Times.now(), user.id());
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
        tx.update("UPDATE \"users\" SET \"bot_token\" = ?, \"updated_at\" = ? WHERE \"id\" = ?", generateBotToken(), Times.now(), id);
    }

    public static boolean emailTaken(Conn conn, String email, Long exceptId) {
        if (email == null) return false;
        return exceptId == null ? conn.exists("SELECT 1 FROM \"users\" WHERE \"email_address\" = ?", email)
            : conn.exists("SELECT 1 FROM \"users\" WHERE \"email_address\" = ? AND \"id\" != ?", email, exceptId);
    }
}
