package campfire.accounts;

import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.web.Halt;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.accounts.custom_styles.JteeditGenerated;
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
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;

/// CSS the administrators add to every page.
@Path("/account/custom_styles")
@RunOnVirtualThread
@NullMarked
public class CustomStylesResource {
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
    AccountService accounts;

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Path("edit")
    public Response edit() {
        requireAdministrator();
        Account account = accounts.account();
        return pages.page((out, v) -> JteeditGenerated.render(out, null, v, account.customStyles()));
    }

    @PUT
    public Response replace(MultipartFormDataInput form) {
        return update(form);
    }

    /// A `custom_styles` value that is not text clears them.
    @PATCH
    public Response update(MultipartFormDataInput form) {
        requireAdministrator();
        Account account = accounts.account();
        Params params = Params.of(routing.request(), form);
        params.require("account");
        boolean given = params.get("account") instanceof Map<?, ?> attributes && attributes.containsKey("custom_styles");
        accounts.updateCustomStyles(account, given, params.string("account", "custom_styles"));
        railsSession.flash().set("notice", "✓");
        return Responses.redirect(origin.url(Paths.editAccountCustomStyles()));
    }

    private void requireAdministrator() {
        if (!viewer.user().canAdminister()) throw new Halt(Responses.head(403));
    }
}
