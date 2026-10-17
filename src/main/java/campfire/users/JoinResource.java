package campfire.users;

import campfire.accounts.HelpContact;
import campfire.accounts.Account;
import campfire.sessions.Authentication;
import campfire.sessions.SessionService;
import campfire.storage.AttachmentChange;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Paths;
import campfire.web.Responses;
import gg.jte.generated.precompiled.users.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Joining the account through its join link.
@Path("/join/{join_code}")
@Access.GuestOnly
@RunOnVirtualThread
@NullMarked
public class JoinResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    UserService users;
    @Inject
    SessionService sessions;
    @Inject
    Authentication authentication;

    @GET
    public Response newUser(@PathParam("join_code") String joinCode) {
        Account account = verifiedAccount(joinCode);
        if (account == null) return Responses.head(404);
        Formats.respondTo(request, "html");
        HelpContact helpContact = sessions.helpContact();
        return pages.page((out, v) -> JtenewGenerated.render(out, null, v, account.joinCode(), helpContact));
    }

    /// Signs the new member in. An email address already taken goes to the sign-in form instead,
    /// prefilled with it.
    @POST
    @SuppressWarnings("unchecked")
    public Response create(@PathParam("join_code") String joinCode) {
        if (verifiedAccount(joinCode) == null) return Responses.head(404);
        request.requireHash("user");
        var params = (Map<String, Object>) Objects.requireNonNull(request.params().get("user"));
        String email = params.get("email_address") instanceof String s ? s : null;
        User user = users.join(params.get("name") instanceof String s ? s : null, email, params.get("password") instanceof String s ? s : null,
            AttachmentChange.of(params, "avatar"));
        if (user == null) {
            String query = email == null ? "" : "?email_address=" + View.cgiEscape(email);
            return Responses.redirect(request.url(Paths.NEW_SESSION + query));
        }
        authentication.start(request, user);
        return Responses.redirect(request.url(Paths.ROOT));
    }

    /// The account, if `joinCode` is its current join code.
    private @Nullable Account verifiedAccount(String joinCode) {
        Account account = users.account();
        return account.joinCode().equals(joinCode) ? account : null;
    }
}
