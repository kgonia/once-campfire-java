package campfire.accounts;

import campfire.sessions.Viewer;
import campfire.web.Halt;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// A new join code, so the old join link stops working.
@Path("/account/join_code")
@RunOnVirtualThread
@NullMarked
public class JoinCodeResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    AccountService accounts;

    @POST
    public Response create() {
        if (!viewer.user().canAdminister()) throw new Halt(Responses.head(403));
        accounts.resetJoinCode(accounts.account());
        return Responses.redirect(origin.url(Paths.editAccount()));
    }
}
