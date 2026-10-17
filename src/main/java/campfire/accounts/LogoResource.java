package campfire.accounts;

import campfire.App;
import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Halt;
import campfire.web.HttpCache;
import campfire.web.Paths;
import campfire.web.Responses;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/// The account logo as a PNG, or the stock app icon without one; public, as it is the PWA's icon.
/// Logo URLs carry the account's version, so browsers may reuse one for five minutes and
/// revalidate it in the background for a week. Answered like a file download: without the page
/// security headers or a body digest.
@Path("/account/logo")
@RunOnVirtualThread
@NullMarked
public class LogoResource {
    static final long MAX_AGE_SECONDS = 5 * 60;
    static final long STALE_WHILE_REVALIDATE_SECONDS = 7 * 24 * 60 * 60;

    @Inject
    CurrentRequest request;
    @Inject
    AccountService accounts;
    @Inject
    App app;

    /// `size=small` is at most 192 pixels square, anything else 512.
    @GET
    @Access.Public
    public Response show() {
        request.markDownload();
        Account account = app.account().account();
        String etag = account == null ? null
            : HttpCache.weakEtag(request, HttpCache.cacheKeyWithVersion("accounts", account.id(), account.updatedAt()), null);
        if (etag != null && HttpCache.fresh(request, etag)) return HttpCache.notModified(etag);
        boolean small = "small".equals(request.params().string("size"));
        java.nio.file.Path logo = accounts.logo(small);
        Response.ResponseBuilder response;
        if (logo != null) {
            response = HttpCache.sendFile(logo, "image/png");
        } else {
            String name = small ? "app-icon-192.png" : "app-icon.png";
            var file = Objects.requireNonNull(app.assets().file(app.assets().path("logos/" + name)));
            response = HttpCache.inline(file.body(), name, "image/png");
        }
        if (etag != null) response.header("ETag", etag);
        return HttpCache.expiresIn(response, MAX_AGE_SECONDS, true, STALE_WHILE_REVALIDATE_SECONDS).build();
    }

    @DELETE
    public Response destroy() {
        request.markDownload();
        if (!request.user().canAdminister()) throw new Halt(Responses.head(403));
        accounts.deleteLogo(accounts.account());
        return Responses.redirect(request.url(Paths.editAccount()));
    }
}
