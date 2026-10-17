package campfire.push;

import campfire.view.Pages;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.Responses;
import gg.jte.generated.precompiled.users.push_subscriptions.JteindexGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The signed-in user's push subscriptions (the user id segment is ignored). Browsers register
/// themselves with a JSON post; the page lists them with a test button each.
@Path("/users/{user_id}/push_subscriptions")
@RunOnVirtualThread
@NullMarked
public class PushSubscriptionResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    PushSubscriptionService subscriptions;

    @GET
    public Response index() {
        Formats.respondTo(request, "html");
        List<PushSubscriptionView> list = subscriptions.list(request.user().id());
        return pages.page((out, v) -> JteindexGenerated.render(out, null, v, list));
    }

    /// 200 when registered or refreshed, 422 for an endpoint that isn't an allowed push service.
    @POST
    @SuppressWarnings("unchecked")
    public Response create() {
        request.wrapJsonParameters("push_subscription");
        long userId = request.user().id();
        request.requireHash("push_subscription");
        var params = (Map<String, Object>) Objects.requireNonNull(request.params().get("push_subscription"));
        var fields = new LinkedHashMap<String, @Nullable String>();
        params.forEach((key, value) -> {
            if (!PushSubscriptionService.FIELDS.contains(key)) return;
            switch (value) {
                case null -> fields.put(key, null);
                case String s -> fields.put(key, s);
                case Number n -> fields.put(key, n.toString());
                case Boolean b -> fields.put(key, b.toString());
                default -> {}
            }
        });
        return Responses.head(subscriptions.register(userId, fields, request.userAgent()) ? 200 : 422);
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        Long subscriptionId = Params.parseId(id);
        if (subscriptionId != null) subscriptions.delete(request.user().id(), subscriptionId);
        return Responses.redirect(request.url(Paths.USER_PUSH_SUBSCRIPTIONS));
    }

    @POST
    @Path("{push_subscription_id}/test_notifications")
    public Response testNotification(@PathParam("push_subscription_id") String id) {
        long userId = request.user().id();
        Long subscriptionId = Params.parseId(id);
        if (subscriptionId == null) throw new campfire.web.RecordNotFound();
        String location = request.url(Paths.USER_PUSH_SUBSCRIPTIONS);
        subscriptions.sendTest(userId, subscriptionId, location);
        return Responses.redirect(location);
    }
}
