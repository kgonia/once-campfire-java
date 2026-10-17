package campfire.accounts;

import campfire.App;
import campfire.cable.Cable;
import campfire.db.Database;
import campfire.storage.AttachmentChange;
import campfire.storage.Blob;
import campfire.storage.Storage;
import campfire.storage.Variation;
import campfire.users.User;
import campfire.users.Users;
import campfire.web.Params;
import campfire.web.RecordNotFound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The account's settings, its logo, its join code, and its people: their roles and removal.
@ApplicationScoped
@NullMarked
public class AccountService {
    @Inject
    Database db;
    @Inject
    App app;
    @Inject
    Storage storage;
    @Inject
    Cable cable;

    /// The account; there is always one once the first run is done, and Rails fails without it.
    public Account account() {
        Account account = app.account().account();
        if (account == null) throw new IllegalStateException("no account");
        return account;
    }

    /// Everyone but bots, by name; administrators also see who is banned.
    public List<User> people(boolean includeBanned) {
        return db.read(conn -> includeBanned ? User.activeOrBannedOrderedWithoutBots(conn) : User.activeOrderedWithoutBots(conn));
    }

    /// Writes only what changed. `settings` are form values, null when none were sent.
    public void update(Account account, @Nullable String name, @Nullable Map<String, String> settings, AttachmentChange logo) {
        String settingsJson = settings == null ? null : account.settingsJsonWith(settings);
        try (var staged = logo.stage(storage)) {
            db.write(tx -> {
                Account.update(tx, account, name, false, null, settingsJson);
                staged.apply(tx, "Account", "accounts", account.id(), "logo");
                return null;
            });
        }
    }

    /// `given` false leaves the styles alone; given null clears them.
    public void updateCustomStyles(Account account, boolean given, @Nullable String customStyles) {
        db.write(tx -> {
            Account.update(tx, account, null, given, customStyles, null);
            return null;
        });
    }

    public void resetJoinCode(Account account) {
        db.write(tx -> {
            Account.resetJoinCode(tx, account.id());
            return null;
        });
    }

    public void deleteLogo(Account account) {
        try (var staged = new AttachmentChange.Delete().stage(storage)) {
            db.write(tx -> {
                staged.apply(tx, "Account", "accounts", account.id(), "logo");
                return null;
            });
        }
    }

    /// The logo as a square PNG of at most 192 (small) or 512 pixels, or null without one that
    /// can be resized.
    public @Nullable Path logo(boolean small) {
        Account account = app.account().account();
        if (account == null) return null;
        Blob logo = storage.attached("Account", account.id(), "logo");
        if (logo == null || !storage.variable(logo)) return null;
        Blob variant = storage.processedVariant(logo, small ? Variation.LOGO_SMALL : Variation.LOGO_LARGE);
        return variant == null ? null : storage.pathFor(variant);
    }

    /// An active person by id parameter, or a 404.
    public User activeUser(@Nullable String id) {
        Long userId = Params.parseId(id);
        return RecordNotFound.require(userId == null ? null : db.read(conn -> User.findActive(conn, userId)));
    }

    /// Anything but "administrator" makes a member.
    public void setRole(User user, @Nullable String role) {
        int newRole = "administrator".equals(role) ? User.ADMINISTRATOR : User.MEMBER;
        if (newRole == user.role()) return;
        db.write(tx -> {
            Users.updateRole(tx, user.id(), newRole);
            return null;
        });
    }

    /// Removes the person from the account; their open sockets are closed for good first.
    public void deactivate(User user) {
        db.write(tx -> {
            cable.disconnectUser(user.id(), false);
            Users.deactivate(tx, user);
            return null;
        });
    }
}
