package campfire.view;

import campfire.App;
import campfire.rooms.RoomService;
import campfire.useragent.Platform;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.Responses;
import gg.jte.TemplateOutput;
import gg.jte.generated.precompiled.sessions.Jteincompatible_browserGenerated;
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
    CurrentRequest request;
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
        var flash = request.flash();
        var user = request.userIfSignedIn();
        String lastRoom = request.cookie("last_room");
        return new View(app, user, account.account(), account.hasLogo(), flash.get("notice"), flash.get("alert"),
            Platform.of(request.userAgent()), request.baseUrl(), request.requestUrl(), request.header("referer"),
            user == null ? null : () -> rooms.lastVisited(user.id(), Params.parseId(lastRoom)).orElse(null));
    }

    /// For renders that end up in broadcasts, shared by every viewer: no user or flash, and URLs
    /// with the request's protocol and host but never its port.
    public View detachedView() {
        var account = app.account();
        return View.detached(app, account.account(), account.hasLogo(), request.protocol() + request.host());
    }

    public Response page(Template template) {
        return page(200, template);
    }

    public Response page(int status, Template template) {
        View v = view();
        v.frameLayout = request.isTurboFrame();
        return render(status, v, template);
    }

    /// Pages whose format was picked from the `Accept` header say they vary on it.
    public Response render(int status, View v, Template template) {
        return response(status, bytes(v, template)).build();
    }

    /// An HTML response for an already rendered page.
    public Response.ResponseBuilder response(int status, byte[] body) {
        var response = Responses.html(status, body);
        if (Formats.variesOnAccept(request)) response.header("Vary", "Accept");
        return response;
    }

    public static byte[] bytes(View v, Template template) {
        var out = new HtmlOut(16384);
        template.render(out, v);
        return out.body();
    }

    /// The page browsers too old for the app get (always HTML, status 200).
    public Response incompatibleBrowser(boolean fullLayout) {
        View v = view();
        v.frameLayout = !fullLayout && request.isTurboFrame();
        return render(200, v, (out, view) -> Jteincompatible_browserGenerated.render(out, null, view));
    }
}
