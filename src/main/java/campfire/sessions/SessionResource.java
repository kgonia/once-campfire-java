package campfire.sessions;

import campfire.accounts.HelpContact;
import campfire.sessions.Viewer;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.sessions.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;

/// Signing in with email and password, and signing out.
@Path("/session")
@RunOnVirtualThread
@NullMarked
public class SessionResource {
    static final String REJECTION = "Too many requests or unauthorized.";

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
    SessionService sessions;
    @Inject
    Authentication authentication;

    /// The sign-in form, or the first run while no one has signed up.
    @GET
    @Path("new")
    @Access.Public
    public Response newSession() {
        if (sessions.noUsers()) return Responses.redirect(origin.url(Paths.FIRST_RUN));
        return signInPage(200, Params.of(routing.request()));
    }

    /// More than ten attempts from one address within three minutes are refused unchecked.
    @POST
    @Access.Public
    public Response create(MultipartFormDataInput form) {
        Params params = Params.of(routing.request(), form);
        if (sessions.rateLimited(origin.remoteIp(), Instant.now())) return rejected(429, params);
        String email = params.string("email_address");
        String password = params.string("password");
        User user = email == null || password == null ? null : sessions.authenticate(email, password);
        if (user == null) return rejected(401, params);
        authentication.start(user);
        return Responses.redirect(authentication.afterSignIn());
    }

    /// Also forgets the push subscription of the device signing out, if it says which.
    @DELETE
    public Response destroy(MultipartFormDataInput form) {
        String endpoint = Params.of(routing.request(), form).string("push_subscription_endpoint");
        if (endpoint != null) sessions.forgetPushSubscription(viewer.user().id(), endpoint);
        authentication.terminate();
        return Responses.redirect(origin.url(Paths.ROOT));
    }

    private Response rejected(int status, Params params) {
        railsSession.flash().now("alert", REJECTION);
        return signInPage(status, params);
    }

    /// Prefilled with the email address that was tried, or that a sign-up found taken.
    private Response signInPage(int status, Params params) {
        Formats.respondTo(routing, "html");
        String email = params.string("email_address");
        HelpContact helpContact = sessions.helpContact();
        return pages.page(status, (out, v) -> JtenewGenerated.render(out, null, v, email, helpContact));
    }
}
