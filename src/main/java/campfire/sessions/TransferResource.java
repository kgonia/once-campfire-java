package campfire.sessions;

import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Responses;
import gg.jte.generated.precompiled.sessions.transfers.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// Signing in on another device with a transfer link (from the profile, or its QR code). Opening
/// the link shows a form that submits itself, so link previews and prefetches don't sign anyone in.
@Path("/session/transfers/{id}")
@RunOnVirtualThread
@Access.Public
@NullMarked
public class TransferResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    SessionService sessions;
    @Inject
    Authentication authentication;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response show() {
        String action = request.path();
        return pages.page((out, v) -> JteshowGenerated.render(out, null, v, action));
    }

    @PUT
    public Response replace(@PathParam("id") String id) {
        return update(id);
    }

    /// A link that's expired, forged, or for a user no longer active is a 400.
    @PATCH
    public Response update(@PathParam("id") String id) {
        User user = sessions.transferredUser(id);
        if (user == null) return Responses.head(400);
        authentication.start(user);
        return Responses.redirect(authentication.afterSignIn());
    }
}
