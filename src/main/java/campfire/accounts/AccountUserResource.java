package campfire.accounts;

import campfire.users.User;
import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.Paths;
import gg.jte.generated.precompiled.accounts.users.Jteindexturbo_streamGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/// The account's people: the next pages of the list (as a Turbo Stream, the only format), and an
/// administrator's role changes and removals.
@Path("/account/users")
@RunOnVirtualThread
@NullMarked
public class AccountUserResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    AccountService accounts;

    @GET
    public Response index() {
        Formats.respondTo(request, "turbo_stream");
        List<User> people = accounts.people(false);
        var page = PeoplePage.of(request.params().string("page"), people.size());
        List<User> shown = people.stream().skip(page.offset()).limit(PeoplePage.PER_PAGE).toList();
        String nextPage = page.isLast() ? null : Long.toString(page.number() + 1);
        byte[] body = Pages.bytes(pages.view(), (out, v) -> Jteindexturbo_streamGenerated.render(out, null, v, shown, nextPage));
        Response response = CurrentRequest.turboStream(body);
        if (Formats.variesOnAccept(request)) response.getHeaders().putSingle("Vary", "Accept");
        return response;
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id) {
        return update(id);
    }

    /// Anything but "administrator" makes the person a member.
    @PATCH
    @Path("{id}")
    public Response update(@PathParam("id") String id) {
        requireAdministrator();
        User user = accounts.activeUser(id);
        request.require("user");
        String role = request.params().get("user") instanceof Map<?, ?> attributes && attributes.get("role") instanceof String s ? s : null;
        accounts.setRole(user, role);
        return request.redirect(Paths.editAccount());
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        requireAdministrator();
        accounts.deactivate(accounts.activeUser(id));
        return request.redirect(Paths.editAccount());
    }

    private void requireAdministrator() {
        if (!request.user().canAdminister()) throw new Halt(request.head(403));
    }
}
