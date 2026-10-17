package campfire.users;

import campfire.rails.Secrets;
import campfire.sessions.SessionService;
import campfire.storage.AttachmentChange;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Paths;
import gg.jte.generated.precompiled.users.profiles.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The signed-in user's own profile; the user id segment is ignored (links say `me`).
@Path("/users/{user_id}/profile")
@RunOnVirtualThread
@NullMarked
public class ProfileResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    UserService users;
    @Inject
    Secrets secrets;

    @GET
    public Response show() {
        Formats.respondTo(request, "html");
        User user = request.user();
        String transferId = SessionService.transferId(secrets, user.id());
        boolean avatarAttached = users.avatarAttached(user.id());
        var memberships = users.profileMemberships(user);
        var profile = new UserPages.ProfileShow(user, avatarAttached, transferId, memberships.shared(), memberships.direct());
        return pages.page((out, v) -> JteshowGenerated.render(out, null, v, profile));
    }

    @PUT
    public Response replace() {
        return update();
    }

    /// Fields not sent stay as they are; an empty avatar field removes the avatar. A new avatar
    /// takes a while to reach every page, which the notice says.
    @PATCH
    @SuppressWarnings("unchecked")
    public Response update() {
        User user = request.user();
        request.requireHash("user");
        var params = (Map<String, Object>) Objects.requireNonNull(request.params().get("user"));
        AttachmentChange avatar = params.get("avatar") == null ? new AttachmentChange.Unchanged() : AttachmentChange.of(params, "avatar");
        users.updateProfile(user, string(params, "name"), string(params, "email_address"), string(params, "password"), string(params, "bio"), avatar);
        String notice = params.get("avatar") != null ? "It may take up to 30 minutes to change everywhere." : "✓";
        return request.redirectWithNotice(request.url(Paths.USER_PROFILE), notice);
    }

    private static @Nullable String string(Map<String, Object> params, String key) {
        return params.get(key) instanceof String s ? s : null;
    }
}
