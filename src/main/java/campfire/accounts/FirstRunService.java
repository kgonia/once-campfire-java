package campfire.accounts;

import campfire.db.Database;
import campfire.db.DbException;
import campfire.rooms.Room;
import campfire.storage.AttachmentChange;
import campfire.storage.Storage;
import campfire.users.User;
import campfire.users.Users;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Setting up a new installation: the account, its first administrator, and the open room
/// everyone starts in.
@ApplicationScoped
@NullMarked
public class FirstRunService {
    static final String ACCOUNT_NAME = "Campfire";
    static final String FIRST_ROOM_NAME = "All Talk";

    @Inject
    Database db;
    @Inject
    Storage storage;

    /// Once there's an account, the first run is over.
    public boolean done() {
        return db.read(Account::any);
    }

    /// The administrator, or null when the email address is taken (a first run submitted twice).
    /// A missing name fails like the NOT NULL column it is.
    public @Nullable User create(@Nullable String name, String emailAddress, String password, AttachmentChange avatar) {
        if (name == null) throw new IllegalStateException("NOT NULL constraint failed: users.name");
        String digest = Users.digest(password);
        try (var staged = avatar.stage(storage)) {
            return db.write(tx -> {
                Account.create(tx, ACCOUNT_NAME);
                User administrator = Users.create(tx, name, emailAddress, digest, User.ADMINISTRATOR, null);
                Room.createFor(tx, Room.OPEN, FIRST_ROOM_NAME, administrator.id(), List.of(administrator.id()));
                staged.apply(tx, "User", "users", administrator.id(), "avatar");
                return User.find(tx, administrator.id());
            });
        } catch (DbException e) {
            if (e.isUniqueViolation()) return null;
            throw e;
        }
    }
}
