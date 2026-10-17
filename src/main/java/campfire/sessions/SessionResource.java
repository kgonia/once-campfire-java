package campfire.sessions;

import campfire.accounts.HelpContact;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Paths;
import campfire.web.Responses;
import gg.jte.generated.precompiled.sessions.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.jspecify.annotations.NullMarked;

/// Signing in with email and password, and signing out.
@Path("/session")
@RunOnVirtualThread
@NullMarked
public class SessionResource {
    static final String REJECTION = "Too many requests or unauthorized.";

    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    SessionService sessions;
    @Inject
    Authentication authentication;

    /// The sign-in form, or the first run while no one has signed up.
    @GET
    @Path("new")
    @Access.Public
    public Response newSession() {
        if (sessions.noUsers()) return Responses.redirect(request.url(Paths.FIRST_RUN));
        return signInPage(200);
    }

    /// More than ten attempts from one address within three minutes are refused unchecked.
    @POST
    @Access.Public
    public Response create() {
        if (sessions.rateLimited(request.remoteIp(), Instant.now())) return rejected(429);
        String email = request.params().string("email_address");
        String password = request.params().string("password");
        User user = email == null || password == null ? null : sessions.authenticate(email, password);
        if (user == null) return rejected(401);
        authentication.start(user);
        return Responses.redirect(authentication.afterSignIn());
    }

    /// Also forgets the push subscription of the device signing out, if it says which.
    @DELETE
    public Response destroy() {
        String endpoint = request.params().string("push_subscription_endpoint");
        if (endpoint != null) sessions.forgetPushSubscription(request.user().id(), endpoint);
        authentication.terminate();
        return Responses.redirect(request.url(Paths.ROOT));
    }

    private Response rejected(int status) {
        request.flash().now("alert", REJECTION);
        return signInPage(status);
    }

    /// Prefilled with the email address that was tried, or that a sign-up found taken.
    private Response signInPage(int status) {
        Formats.respondTo(request, "html");
        String email = request.params().string("email_address");
        HelpContact helpContact = sessions.helpContact();
        return pages.page(status, (out, v) -> JtenewGenerated.render(out, null, v, email, helpContact));
    }
}
