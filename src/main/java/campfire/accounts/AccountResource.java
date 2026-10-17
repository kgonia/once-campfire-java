package campfire.accounts;

import campfire.sessions.Viewer;
import campfire.storage.AttachmentChange;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Halt;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.accounts.JteeditGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The account settings page: its name, logo and room creation setting (administrators only),
/// the join link, and everyone in the account.
@Path("/account")
@RunOnVirtualThread
@NullMarked
public class AccountResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RailsSession railsSession;
    @Inject
    RoutingContext routing;
    @Inject
    Pages pages;
    @Inject
    AccountService accounts;

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("edit")
    public Response edit(@RestQuery @Nullable String page) {
        Account account = accounts.account();
        boolean canAdminister = viewer.user().canAdminister();
        List<User> people = accounts.people(canAdminister);
        var shown = PeoplePage.of(page, people.size());
        var accountPage = new AccountPage(account, people.stream().filter(User::isAdministrator).toList(),
            people.stream().filter(user -> !user.isAdministrator()).toList(), shown.isLast() ? null : Long.toString(shown.number() + 1));
        return pages.page((out, v) -> JteeditGenerated.render(out, null, v, accountPage));
    }

    @PUT
    public Response replace(MultipartFormDataInput form) {
        return update(form);
    }

    @PATCH
    public Response update(MultipartFormDataInput form) {
        requireAdministrator();
        Account account = accounts.account();
        Params params = Params.of(routing.request(), form);
        params.require("account");
        if (!(params.get("account") instanceof Map<?, ?> attributes)) throw new IllegalStateException("undefined method 'permit' for String");
        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) attributes;
        String name = fields.get("name") instanceof String s ? s : null;
        accounts.update(account, name, settings(fields.get("settings")), AttachmentChange.of(fields, "logo"));
        railsSession.flash().set("notice", "✓");
        return Responses.redirect(origin.url(Paths.editAccount()));
    }

    /// The submitted settings as strings, or null when no settings hash was sent.
    private static @Nullable Map<String, String> settings(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        var settings = new LinkedHashMap<String, String>();
        map.forEach((key, setting) -> settings.put(key.toString(), setting instanceof String s ? s : ""));
        return settings;
    }

    private void requireAdministrator() {
        if (!viewer.user().canAdminister()) throw new Halt(Responses.head(403));
    }
}
