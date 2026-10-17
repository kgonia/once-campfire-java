package campfire.accounts;

import campfire.sessions.Authentication;
import campfire.storage.AttachmentChange;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Access;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.first_runs.JteshowGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Objects;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;

/// The setup form a new installation opens with; afterwards both actions go to the root.
@Path("/first_run")
@RunOnVirtualThread
@Access.Public
@NullMarked
public class FirstRunResource {
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    @Inject
    Pages pages;
    @Inject
    FirstRunService firstRuns;
    @Inject
    Authentication authentication;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response show() {
        if (firstRuns.done()) return Responses.redirect(origin.url(Paths.ROOT));
        return pages.page((out, v) -> JteshowGenerated.render(out, null, v));
    }

    /// Signs the new administrator in.
    @POST
    @SuppressWarnings("unchecked")
    public Response create(MultipartFormDataInput form) {
        if (firstRuns.done()) return Responses.redirect(origin.url(Paths.ROOT));
        Params params = Params.of(routing.request(), form);
        params.requireHash("user");
        var user = (Map<String, Object>) Objects.requireNonNull(params.get("user"));
        User administrator = firstRuns.create(stringOrNull(user, "name"), stringOrEmpty(user, "email_address"), stringOrEmpty(user, "password"),
            AttachmentChange.of(user, "avatar"));
        if (administrator != null) authentication.start(administrator);
        return Responses.redirect(origin.url(Paths.ROOT));
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(Map<String, Object> params, String key) {
        return params.get(key) instanceof String s ? s : null;
    }

    private static String stringOrEmpty(Map<String, Object> params, String key) {
        String value = stringOrNull(params, key);
        return value == null ? "" : value;
    }
}
