package campfire.users;

import campfire.App;
import campfire.storage.Blob;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.HttpCache;
import campfire.web.Paths;
import campfire.web.Responses;
import gg.jte.generated.precompiled.users.avatars.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/// A user's avatar, addressed by a signed token rather than the user id: the uploaded image as a
/// square, the stock bot avatar, or the user's initials. Avatar URLs carry the user's version, so
/// browsers may reuse one for half an hour and revalidate it in the background for a week.
/// Answered like a file download: without the page security headers or a body digest.
@Path("/users/{user_id}/avatar")
@RunOnVirtualThread
@NullMarked
public class AvatarResource {
    /// The digest of the initials SVG template, part of the ETag whenever that template could be
    /// the one rendered.
    static final String TEMPLATE_DIGEST = "d500db55e2a67222018ef0156839c3c9";
    static final long MAX_AGE_SECONDS = 30 * 60;
    static final long STALE_WHILE_REVALIDATE_SECONDS = 7 * 24 * 60 * 60;

    @Inject
    CurrentRequest request;
    @Inject
    UserService users;
    @Inject
    App app;

    /// A forged token is a 404 without a page.
    @GET
    public Response show(@PathParam("user_id") String token) {
        User user = users.fromAvatarToken(token);
        if (user == null) return Responses.head(404);
        String etag = HttpCache.weakEtag(request, HttpCache.cacheKeyWithVersion("users", user.id(), user.updatedAt()),
            initialsMayRender() ? TEMPLATE_DIGEST : null);
        if (HttpCache.fresh(request, etag)) return HttpCache.notModified(etag);

        Response.ResponseBuilder response;
        Blob variant = users.avatarVariant(user);
        if (variant != null) {
            response = HttpCache.sendFile(users.pathFor(variant), "image/webp");
        } else if (user.isBot()) {
            var file = Objects.requireNonNull(app.assets().file(app.assets().path("default-bot-avatar.svg")));
            response = HttpCache.inline(file.body(), "default-bot-avatar.svg", "image/svg+xml");
        } else {
            byte[] svg = Pages.bytes(null, (out, v) -> JteshowGenerated.render(out, null, user.id(), user.initials()));
            response = Response.ok(svg).header("Content-Type", "image/svg+xml; charset=utf-8");
            if (Formats.variesOnAccept(request)) response.header("Vary", "Accept");
        }
        response.header("ETag", etag);
        return HttpCache.expiresIn(response, MAX_AGE_SECONDS, true, STALE_WHILE_REVALIDATE_SECONDS).build();
    }

    @DELETE
    public Response destroy() {
        users.deleteAvatar(request.user().id());
        return Responses.redirect(request.url(Paths.USER_PROFILE));
    }

    /// The SVG template is found for these formats: anything, SVG, or none that Rails knows
    /// (`Accept: image/*`).
    private boolean initialsMayRender() {
        List<String> formats = Formats.of(request);
        return formats.isEmpty() || formats.contains("all") || formats.contains("svg");
    }
}
