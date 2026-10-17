package campfire.accounts;

import campfire.sessions.Viewer;
import campfire.users.User;
import campfire.view.Pages;
import campfire.web.Formats;
import campfire.web.Halt;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.accounts.users.Jteindexturbo_streamGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
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
import org.jboss.resteasy.reactive.RestQuery;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The account's people: the next pages of the list (as a Turbo Stream, the only format), and an
/// administrator's role changes and removals.
@Path("/account/users")
@RunOnVirtualThread
@NullMarked
public class AccountUserResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    @Inject
    Pages pages;
    @Inject
    AccountService accounts;

    @GET
    public Response index(@RestQuery @Nullable String page) {
        Formats.respondTo(routing, "turbo_stream");
        List<User> people = accounts.people(false);
        var shownPage = PeoplePage.of(page, people.size());
        List<User> shown = people.stream().skip(shownPage.offset()).limit(PeoplePage.PER_PAGE).toList();
        String nextPage = shownPage.isLast() ? null : Long.toString(shownPage.number() + 1);
        byte[] body = Pages.bytes(pages.view(), (out, v) -> Jteindexturbo_streamGenerated.render(out, null, v, shown, nextPage));
        return Responses.turboStream(body);
    }

    @PUT
    @Path("{id}")
    public Response replace(@PathParam("id") String id, MultipartFormDataInput form) {
        return update(id, form);
    }

    /// Anything but "administrator" makes the person a member.
    @PATCH
    @Path("{id}")
    public Response update(@PathParam("id") String id, MultipartFormDataInput form) {
        requireAdministrator();
        User user = accounts.activeUser(id);
        Params params = Params.of(routing.request(), form);
        params.require("user");
        String role = params.get("user") instanceof Map<?, ?> attributes && attributes.get("role") instanceof String s ? s : null;
        accounts.setRole(user, role);
        return Responses.redirect(origin.url(Paths.editAccount()));
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        requireAdministrator();
        accounts.deactivate(accounts.activeUser(id));
        return Responses.redirect(origin.url(Paths.editAccount()));
    }

    private void requireAdministrator() {
        if (!viewer.user().canAdminister()) throw new Halt(Responses.head(403));
    }
}
