package campfire.view;

import campfire.App;
import campfire.rooms.RoomService;
import campfire.sessions.Viewer;
import campfire.useragent.Platform;
import campfire.web.Cookies;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.RailsSession;
import campfire.web.RequestOrigin;
import campfire.web.Responses;
import gg.jte.TemplateOutput;
import gg.jte.generated.precompiled.sessions.Jteincompatible_browserGenerated;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/// Renders templates for the current request: pages in the full layout, or in the bare frame
/// layout when Turbo asks for a frame (a `Turbo-Frame` header), and fragments without a layout.
@RequestScoped
public class Pages {
    @FunctionalInterface
    public interface Template {
        void render(TemplateOutput out, View v);
    }

    @Inject
    Viewer viewer;
    @Inject
    RailsSession railsSession;
    @Inject
    Cookies cookies;
    @Inject
    RequestOrigin origin;
    @Inject
    RoutingContext routing;
    @Inject
    App app;
    @Inject
    RoomService rooms;

    /// The views handed out for this request, to tell whether a page linked the stylesheets.
    private final java.util.List<View> views = new java.util.ArrayList<>();

    /// The view context for this request: who is signed in, the flash, the account and URLs.
    public View view() {
        View v = newView();
        views.add(v);
        return v;
    }

    /// Whether this request rendered a page in the application layout, whose stylesheets the
    /// response's `Link` header preloads.
    public boolean stylesheetsLinked() {
        return views.stream().anyMatch(v -> v.stylesheetsLinked);
    }

    private View newView() {
        App.AccountState account = app.account();
        var flash = railsSession.flash();
        var user = viewer.userIfSignedIn();
        String lastRoom = cookies.get("last_room");
        HttpServerRequest request = routing.request();
        return new View(app, user, account.account(), account.hasLogo(), flash.get("notice"), flash.get("alert"),
            Platform.of(request.getHeader("user-agent")), origin.baseUrl(), origin.requestUrl(), request.getHeader("referer"),
            user == null ? null : () -> rooms.lastVisited(user.id(), Params.parseId(lastRoom)).orElse(null));
    }

    /// For renders that end up in broadcasts, shared by every viewer: no user or flash, and URLs
    /// with the request's protocol and host but never its port.
    public View detachedView() {
        var account = app.account();
        return View.detached(app, account.account(), account.hasLogo(), origin.protocol() + origin.host());
    }

    public Response page(Template template) {
        return page(200, template);
    }

    public Response page(int status, Template template) {
        View v = view();
        v.frameLayout = Formats.isTurboFrame(routing);
        return render(status, v, template);
    }

    public Response render(int status, View v, Template template) {
        return response(status, bytes(v, template)).build();
    }

    /// An HTML response for an already rendered page.
    public Response.ResponseBuilder response(int status, byte[] body) {
        return Responses.html(status, body);
    }

    public static byte[] bytes(View v, Template template) {
        var out = new HtmlOut(16384);
        template.render(out, v);
        return out.body();
    }

    /// The page browsers too old for the app get (always HTML, status 200).
    public Response incompatibleBrowser(boolean fullLayout) {
        View v = view();
        v.frameLayout = !fullLayout && Formats.isTurboFrame(routing);
        return render(200, v, (out, view) -> Jteincompatible_browserGenerated.render(out, null, view));
    }
}
