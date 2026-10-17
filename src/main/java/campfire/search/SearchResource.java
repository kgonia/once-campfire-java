package campfire.search;

import campfire.view.Pages;
import campfire.view.View;
import campfire.web.CurrentRequest;
import campfire.web.Params;
import campfire.web.Paths;
import campfire.web.ResponseCache;
import campfire.web.Responses;
import gg.jte.generated.precompiled.searches.JteindexGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Path("/searches")
@RunOnVirtualThread
@NullMarked
public class SearchResource {
    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    ResponseCache cache;
    @Inject
    SearchService searches;

    @GET
    public Response index() {
        String q = submittedQuery();
        var cached = cache.lookup(request, "searches#index");
        if (cached.isPresent()) return cached.get();
        View v = pages.view();
        v.frameLayout = request.isTurboFrame();
        SearchPage page = searches.page(v, request.user().id(), q, Params.parseId(request.cookie("last_room")));
        return pages.render(200, v, (out, view) -> JteindexGenerated.render(out, null, view, page));
    }

    @POST
    public Response create() {
        String query = searches.record(request.user().id(), submittedQuery());
        return Responses.redirect(request.url(Paths.searches(query)));
    }

    @DELETE
    @Path("clear")
    public Response clear() {
        searches.clear(request.user().id(), submittedQuery());
        return Responses.redirect(request.url(Paths.SEARCHES));
    }

    /// `q`, if given as a single value; an array or hash fails as it does in Rails.
    private @Nullable String submittedQuery() {
        return switch (request.params().get("q")) {
            case null -> null;
            case String s -> s;
            default -> throw new IllegalStateException("undefined method 'gsub'");
        };
    }
}
