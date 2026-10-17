package campfire.search;

import campfire.sessions.Viewer;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.Cookies;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.ResponseCache;
import campfire.web.Responses;
import gg.jte.generated.precompiled.searches.JteindexGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Path("/searches")
@RunOnVirtualThread
@NullMarked
public class SearchResource {
    @Inject
    Viewer viewer;
    @Inject
    RequestOrigin origin;
    @Inject
    RailsSession railsSession;
    @Inject
    Cookies cookies;
    @Inject
    RoutingContext routing;
    @Inject
    Pages pages;
    @Inject
    ResponseCache cache;
    @Inject
    SearchService searches;

    @GET
    public Response index() {
        String q = submittedQuery(Params.of(routing.request()));
        var cached = cache.lookup(viewer, railsSession, origin, routing, "searches#index");
        if (cached.isPresent()) return cached.get();
        View v = pages.view();
        v.frameLayout = Formats.isTurboFrame(routing);
        SearchPage page = searches.page(v, viewer.user().id(), q, Params.parseId(cookies.get("last_room")));
        return pages.render(200, v, (out, view) -> JteindexGenerated.render(out, null, view, page));
    }

    @POST
    public Response create(MultipartFormDataInput form) {
        String query = searches.record(viewer.user().id(), submittedQuery(Params.of(routing.request(), form)));
        return Responses.redirect(origin.url(Paths.searches(query)));
    }

    @DELETE
    @Path("clear")
    public Response clear(MultipartFormDataInput form) {
        searches.clear(viewer.user().id(), submittedQuery(Params.of(routing.request(), form)));
        return Responses.redirect(origin.url(Paths.SEARCHES));
    }

    /// `q`, if given as a single value; an array or hash fails as it does in Rails.
    private static @Nullable String submittedQuery(Params params) {
        return switch (params.get("q")) {
            case null -> null;
            case String s -> s;
            default -> throw new IllegalStateException("undefined method 'gsub'");
        };
    }
}
