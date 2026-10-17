package campfire.accounts;

import campfire.users.User;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The account settings page: everyone, administrators first. `nextPage` is the page number the
/// people list loads next when it is longer than one page, else null.
@NullMarked
public record AccountPage(Account account, List<User> administrators, List<User> members, @Nullable String nextPage) {
    /// The form action Rails writes for the account (its id where a format would go).
    public String action() {
        return "/account." + account.id();
    }
}
