package campfire.users;

import campfire.rails.Secrets;
import campfire.sessions.SessionService;
import campfire.sessions.Viewer;
import campfire.storage.AttachmentChange;
import campfire.view.Pages;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.users.profiles.JteshowGenerated;
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
import java.util.Map;
import java.util.Objects;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The signed-in user's own profile; the user id segment is ignored (links say `me`).
@Path("/users/{user_id}/profile")
@RunOnVirtualThread
@NullMarked
public class ProfileResource {
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
    UserService users;
    @Inject
    Secrets secrets;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response show() {
        User user = viewer.user();
        String transferId = SessionService.transferId(secrets, user.id());
        boolean avatarAttached = users.avatarAttached(user.id());
        var memberships = users.profileMemberships(user);
        var profile = new UserPages.ProfileShow(user, avatarAttached, transferId, memberships.shared(), memberships.direct());
        return pages.page((out, v) -> JteshowGenerated.render(out, null, v, profile));
    }

    @PUT
    public Response replace(MultipartFormDataInput form) {
        return update(form);
    }

    /// Fields not sent stay as they are; an empty avatar field removes the avatar. A new avatar
    /// takes a while to reach every page, which the notice says.
    @PATCH
    @SuppressWarnings("unchecked")
    public Response update(MultipartFormDataInput form) {
        User user = viewer.user();
        Params submitted = Params.of(routing.request(), form);
        submitted.requireHash("user");
        var params = (Map<String, Object>) Objects.requireNonNull(submitted.get("user"));
        AttachmentChange avatar = params.get("avatar") == null ? new AttachmentChange.Unchanged() : AttachmentChange.of(params, "avatar");
        users.updateProfile(user, string(params, "name"), string(params, "email_address"), string(params, "password"), string(params, "bio"), avatar);
        String notice = params.get("avatar") != null ? "It may take up to 30 minutes to change everywhere." : "✓";
        railsSession.flash().set("notice", notice);
        return Responses.redirect(origin.url(Paths.USER_PROFILE));
    }

    private static @Nullable String string(Map<String, Object> params, String key) {
        return params.get(key) instanceof String s ? s : null;
    }
}
