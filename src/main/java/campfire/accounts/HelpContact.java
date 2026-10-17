package campfire.accounts;

import campfire.db.Conn;
import campfire.users.User;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Whom the sign-in and account pages say to email for help: the first administrator.
@NullMarked
public record HelpContact(String name, String emailAddress) {
    public static @Nullable HelpContact find(Conn conn) {
        User owner = User.firstAdministrator(conn);
        return owner == null ? null : new HelpContact(owner.name(), owner.emailAddress() == null ? "" : owner.emailAddress());
    }
}
