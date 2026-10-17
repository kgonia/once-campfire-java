package campfire.accounts;

import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.Paths;
import gg.jte.generated.precompiled.accounts.custom_styles.JteeditGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/// CSS the administrators add to every page.
@Path("/account/custom_styles")
@RunOnVirtualThread
@NullMarked
public class CustomStylesResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    AccountService accounts;

    @GET
    @Path("edit")
    public Response edit() {
        requireAdministrator();
        Account account = accounts.account();
        Formats.respondTo(request, "html");
        return pages.page((out, v) -> JteeditGenerated.render(out, null, v, account.customStyles()));
    }

    @PUT
    public Response replace() {
        return update();
    }

    /// A `custom_styles` value that is not text clears them.
    @PATCH
    public Response update() {
        requireAdministrator();
        Account account = accounts.account();
        request.require("account");
        boolean given = request.params().get("account") instanceof Map<?, ?> attributes && attributes.containsKey("custom_styles");
        accounts.updateCustomStyles(account, given, request.params().string("account", "custom_styles"));
        return request.redirectWithNotice(Paths.editAccountCustomStyles(), "✓");
    }

    private void requireAdministrator() {
        if (!request.user().canAdminister()) throw new Halt(request.head(403));
    }
}
