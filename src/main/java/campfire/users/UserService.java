package campfire.users;

import campfire.accounts.Account;
import campfire.cable.Broadcasts;
import campfire.cable.Cable;
import campfire.db.Database;
import campfire.db.DbException;
import campfire.messages.Message;
import campfire.rails.Secrets;
import campfire.rooms.Room;
import campfire.storage.AttachmentChange;
import campfire.storage.Blob;
import campfire.storage.Storage;
import campfire.storage.Variation;
import campfire.web.Params;
import campfire.web.RecordNotFound;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Joining with the account's join code, profiles and avatars, and banning.
@ApplicationScoped
@NullMarked
public class UserService {
    @Inject
    Database db;
    @Inject
    Storage storage;
    @Inject
    Secrets secrets;
    @Inject
    Cable cable;
    @Inject
    Broadcasts broadcasts;

    /// A user by id parameter, or a 404.
    public User find(@Nullable String id) {
        Long userId = Params.parseId(id);
        return RecordNotFound.require(userId == null ? null : db.read(conn -> User.find(conn, userId)));
    }

    /// The account, which has to exist for anyone to join it.
    public Account account() {
        Account account = db.read(Account::first);
        if (account == null) throw new IllegalStateException("undefined method 'join_code' for nil");
        return account;
    }

    /// A new member, or null when the email address is taken. A missing name fails like the
    /// NOT NULL column it is; a missing password leaves the user unable to sign in.
    public @Nullable User join(@Nullable String name, @Nullable String emailAddress, @Nullable String password, AttachmentChange avatar) {
        if (name == null) throw new IllegalStateException("NOT NULL constraint failed: users.name");
        String digest = password == null || password.isEmpty() ? null : Users.digest(password);
        try (var staged = avatar.stage(storage)) {
            return db.write(tx -> {
                User user = Users.create(tx, name, emailAddress, digest, User.MEMBER, null);
                staged.apply(tx, "User", "users", user.id(), "avatar");
                return User.find(tx, user.id());
            });
        } catch (DbException e) {
            if (e.isUniqueViolation()) return null;
            throw e;
        }
    }

    /// The id of one of the user's rooms.
    public java.util.Optional<Long> roomForUser(long userId, @Nullable Long roomId) {
        if (roomId == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(db.read(conn -> Room.findForUser(conn, userId, roomId))).map(Room::id);
    }

    /// Active users whose name contains `query` (any user without one), by name; a room's members
    /// only when `roomId` is given.
    public List<User> autocompletable(@Nullable Long roomId, @Nullable String query) {
        return db.read(conn -> User.autocompletable(conn, roomId, query, -1, 0));
    }

    public boolean avatarAttached(long userId) {
        return storage.attached("User", userId, "avatar") != null;
    }

    public UserPages.ProfileMemberships profileMemberships(User user) {
        return db.read(conn -> UserPages.profileMemberships(conn, user));
    }

    /// `password`, when given and not empty, replaces the current one.
    public void updateProfile(User user, @Nullable String name, @Nullable String emailAddress, @Nullable String password, @Nullable String bio,
                              AttachmentChange avatar) {
        String digest = password == null || password.isEmpty() ? null : Users.digest(password);
        var changes = new Users.Changes(name, emailAddress, digest, bio);
        try (var staged = avatar.stage(storage)) {
            db.write(tx -> {
                Users.update(tx, user, changes);
                staged.apply(tx, "User", "users", user.id(), "avatar");
                return null;
            });
        }
    }

    public void deleteAvatar(long userId) {
        try (var staged = new AttachmentChange.Delete().stage(storage)) {
            db.write(tx -> {
                staged.apply(tx, "User", "users", userId, "avatar");
                return null;
            });
        }
    }

    /// The user an avatar URL names, or null when its token is forged. The token never expires,
    /// so a deleted user's is a 404 of its own.
    public @Nullable User fromAvatarToken(@Nullable String token) {
        Long id = token == null ? null : secrets.verifySignedId("User", token, "avatar", Instant.now());
        if (id == null) return null;
        return RecordNotFound.require(db.read(conn -> User.find(conn, id)));
    }

    /// The uploaded avatar as a square of at most 512 pixels, or null without an image avatar.
    public @Nullable Blob avatarVariant(User user) {
        Blob avatar = storage.attached("User", user.id(), "avatar");
        if (avatar == null || !storage.variable(avatar)) return null;
        return storage.processedVariant(avatar, Variation.AVATAR_SQUARE);
    }

    public java.nio.file.Path pathFor(Blob blob) {
        return storage.pathFor(blob);
    }

    /// Signs the user out everywhere and keeps them and their addresses out; their messages are
    /// deleted in the background, each disappearing from its room as it goes.
    public void ban(User user) {
        db.write(tx -> {
            Users.ban(tx, user);
            tx.afterCommit(() -> {
                cable.disconnectUser(user.id(), false);
                Thread.ofVirtual().name("remove-banned-content-" + user.id()).start(() -> removeMessages(user.id()));
            });
            return null;
        });
    }

    public void unban(User user) {
        db.write(tx -> {
            Users.unban(tx, user);
            return null;
        });
    }

    private void removeMessages(long userId) {
        try {
            List<Message> messages = db.read(conn -> Message.byCreator(conn, userId));
            for (Message message : messages) {
                Long blobId = db.write(tx -> Message.destroy(tx, message, true));
                if (blobId != null) storage.purgeLater(blobId);
                Room room = db.read(conn -> Room.find(conn, message.roomId()));
                if (room != null) broadcasts.messageRemove(room, message);
            }
        } catch (RuntimeException e) {
            Log.error("Could not remove the banned user's messages", e);
        }
    }
}
