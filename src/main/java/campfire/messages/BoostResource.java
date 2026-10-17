package campfire.messages;

import campfire.view.Pages;
import campfire.view.View;
import campfire.web.CurrentRequest;
import campfire.web.Paths;
import campfire.web.RecordNotFound;
import campfire.web.Responses;
import gg.jte.generated.precompiled.messages.boosts.JteindexGenerated;
import gg.jte.generated.precompiled.messages.boosts.JtenewGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;

/// A message's boosts, and the form for adding one (shown in a Turbo Frame under the message).
@Path("/messages/{message_id}/boosts")
@RunOnVirtualThread
@NullMarked
public class BoostResource {
    @Inject
    CurrentRequest request;
    @Inject
    BoostService boosts;
    @Inject
    Pages pages;

    @GET
    public Response index(@PathParam("message_id") String messageId) {
        Message message = boosts.reachableMessage(request.user(), messageId);
        View v = pages.view();
        v.frameLayout = request.isTurboFrame();
        MessageView view = boosts.view(v, message);
        return pages.render(200, v, (out, page) -> JteindexGenerated.render(out, null, page, view));
    }

    @GET
    @Path("new")
    public Response newBoost(@PathParam("message_id") String messageId) {
        Message message = boosts.reachableMessage(request.user(), messageId);
        View v = pages.view();
        v.frameLayout = request.isTurboFrame();
        var boosting = boosts.boosting(v, message, request.user());
        return pages.render(200, v, (out, page) -> JtenewGenerated.render(out, null, page, boosting.message(), boosting.booster()));
    }

    @POST
    public Response create(@PathParam("message_id") String messageId) {
        Message message = boosts.reachableMessage(request.user(), messageId);
        request.requireHash("boost");
        String content = request.params().get("boost", "content") instanceof String s ? s : null;
        boosts.create(pages.detachedView(), message, request.user(), content);
        return Responses.redirect(request.url(Paths.messageBoosts(message.id())));
    }

    /// Only one's own boosts; 204 since Rails has no template for this.
    @DELETE
    @Path("{id}")
    public Response destroy(@PathParam("message_id") String messageId, @PathParam("id") String id) {
        Message message = boosts.reachableMessage(request.user(), messageId);
        Boost boost = RecordNotFound.require(boosts.findOwn(message, request.user(), id));
        boosts.delete(message, boost);
        return Responses.head(204);
    }
}
