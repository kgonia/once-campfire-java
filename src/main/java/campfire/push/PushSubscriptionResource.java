package campfire.push;

import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.generated.precompiled.users.push_subscriptions.JteindexGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
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
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    @Inject
    Pages pages;
    @Inject
    PushSubscriptionService subscriptions;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index() {
        List<PushSubscriptionView> list = subscriptions.list(viewer.user().id());
        return pages.page((out, v) -> JteindexGenerated.render(out, null, v, list));
    }

    /// 200 when registered or refreshed, 422 for an endpoint that isn't an allowed push service.
    @POST
    @SuppressWarnings("unchecked")
    public Response create(byte[] body) {
        Params submitted = Params.of(routing.request(), body);
        submitted.wrapJson("push_subscription");
        long userId = viewer.user().id();
        submitted.requireHash("push_subscription");
        var params = (Map<String, Object>) Objects.requireNonNull(submitted.get("push_subscription"));
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
        return Responses.head(subscriptions.register(userId, fields, routing.request().getHeader("user-agent")) ? 200 : 422);
    }

    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("id") String id) {
        Long subscriptionId = Params.parseId(id);
        if (subscriptionId != null) subscriptions.delete(viewer.user().id(), subscriptionId);
        return Responses.redirect(origin.url(Paths.USER_PUSH_SUBSCRIPTIONS));
    }

    @POST
    @Path("{push_subscription_id}/test_notifications")
    public Response testNotification(@PathParam("push_subscription_id") String id) {
        long userId = viewer.user().id();
        Long subscriptionId = Params.parseId(id);
        if (subscriptionId == null) throw new campfire.web.RecordNotFound();
        String location = origin.url(Paths.USER_PUSH_SUBSCRIPTIONS);
        subscriptions.sendTest(userId, subscriptionId, location);
        return Responses.redirect(location);
    }
}
