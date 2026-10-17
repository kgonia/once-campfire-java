package campfire.accounts;

import campfire.storage.AttachmentChange;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.Paths;
import gg.jte.generated.precompiled.accounts.JteeditGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The account settings page: its name, logo and room creation setting (administrators only),
/// the join link, and everyone in the account.
@Path("/account")
@RunOnVirtualThread
@NullMarked
public class AccountResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    AccountService accounts;

    @GET
    @Path("edit")
    public Response edit() {
        Account account = accounts.account();
        Formats.respondTo(request, "html");
        boolean canAdminister = request.user().canAdminister();
        List<User> people = accounts.people(canAdminister);
        var page = PeoplePage.of(request.params().string("page"), people.size());
        var accountPage = new AccountPage(account, people.stream().filter(User::isAdministrator).toList(),
            people.stream().filter(user -> !user.isAdministrator()).toList(), page.isLast() ? null : Long.toString(page.number() + 1));
        return pages.page((out, v) -> JteeditGenerated.render(out, null, v, accountPage));
    }

    @PUT
    public Response replace() {
        return update();
    }

    @PATCH
    public Response update() {
        requireAdministrator();
        Account account = accounts.account();
        request.require("account");
        if (!(request.params().get("account") instanceof Map<?, ?> attributes)) throw new IllegalStateException("undefined method 'permit' for String");
        @SuppressWarnings("unchecked")
        var params = (Map<String, Object>) attributes;
        String name = params.get("name") instanceof String s ? s : null;
        accounts.update(account, name, settings(params.get("settings")), AttachmentChange.of(params, "logo"));
        return request.redirectWithNotice(Paths.editAccount(), "✓");
    }

    /// The submitted settings as strings, or null when no settings hash was sent.
    private static @Nullable Map<String, String> settings(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        var settings = new LinkedHashMap<String, String>();
        map.forEach((key, setting) -> settings.put(key.toString(), setting instanceof String s ? s : ""));
        return settings;
    }

    private void requireAdministrator() {
        if (!request.user().canAdminister()) throw new Halt(request.head(403));
    }
}
