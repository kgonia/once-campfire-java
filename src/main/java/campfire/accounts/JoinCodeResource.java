package campfire.accounts;

import campfire.web.CurrentRequest;
import campfire.web.Halt;
import campfire.web.Paths;
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
    CurrentRequest request;
    @Inject
    AccountService accounts;

    @POST
    public Response create() {
        if (!request.user().canAdminister()) throw new Halt(request.head(403));
        accounts.resetJoinCode(accounts.account());
        return request.redirect(Paths.editAccount());
    }
}
