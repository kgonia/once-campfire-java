package campfire.accounts;

import campfire.sessions.Authentication;
import campfire.storage.AttachmentChange;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.CurrentRequest;
import campfire.web.Paths;
import campfire.web.Responses;
import gg.jte.generated.precompiled.first_runs.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/// The setup form a new installation opens with; afterwards both actions go to the root.
@Path("/first_run")
@RunOnVirtualThread
@Access.Public
@NullMarked
public class FirstRunResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    FirstRunService firstRuns;
    @Inject
    Authentication authentication;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response show() {
        if (firstRuns.done()) return Responses.redirect(request.url(Paths.ROOT));
        return pages.page((out, v) -> JteshowGenerated.render(out, null, v));
    }

    /// Signs the new administrator in.
    @POST
    @SuppressWarnings("unchecked")
    public Response create() {
        if (firstRuns.done()) return Responses.redirect(request.url(Paths.ROOT));
        request.requireHash("user");
        var user = (Map<String, Object>) Objects.requireNonNull(request.params().get("user"));
        User administrator = firstRuns.create(stringOrNull(user, "name"), stringOrEmpty(user, "email_address"), stringOrEmpty(user, "password"),
            AttachmentChange.of(user, "avatar"));
        if (administrator != null) authentication.start(request, administrator);
        return Responses.redirect(request.url(Paths.ROOT));
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(Map<String, Object> params, String key) {
        return params.get(key) instanceof String s ? s : null;
    }

    private static String stringOrEmpty(Map<String, Object> params, String key) {
        String value = stringOrNull(params, key);
        return value == null ? "" : value;
    }
}
