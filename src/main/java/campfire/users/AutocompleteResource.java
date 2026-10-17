package campfire.users;

import campfire.rails.Json;
import campfire.rails.RubyStrings;
import campfire.rails.Secrets;
import campfire.richtext.Html;
import campfire.view.Pages;
import campfire.view.View;
import campfire.web.CurrentRequest;
import campfire.web.Formats;
import campfire.web.Params;
import campfire.web.Responses;
import gg.jte.generated.precompiled.autocompletable.users.JteindexGenerated;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Active users matching a name, for mentions and the user pickers, twenty per page by name. The
/// editor's mention prompt gets `<lexxy-prompt-item>`s (filtering with `filter`), the pickers JSON
/// (filtering with `query`) with the total in `X-Total-Count` and the next page in `Link`.
@Path("/autocompletable/users")
@RunOnVirtualThread
@NullMarked
public class AutocompleteResource {
    static final int PAGE_SIZE = 20;

    @Inject
    CurrentRequest request;
    @Inject
    Pages pages;
    @Inject
    UserService users;
    @Inject
    Secrets secrets;

    /// `room_id` narrows to that room's members; a room the user isn't in is a 404.
    @GET
    public Response index() {
        Long roomId = null;
        String room = request.params().present("room_id");
        if (room != null) roomId = users.roomForUser(request.user().id(), Params.parseId(room)).orElseThrow(campfire.web.RecordNotFound::new);
        String query = request.params().present("filter");
        if (query == null) query = request.params().present("query");

        List<User> matches = users.autocompletable(roomId, query);
        long page = Math.clamp(pageParam(), 1, 1_000_000_000);
        List<UserPages.MentionUser> shown = matches.stream().skip((page - 1) * PAGE_SIZE).limit(PAGE_SIZE)
            .map(user -> UserPages.MentionUser.of(secrets, user)).toList();

        String format = Formats.respondTo(request, "html", "json");
        View v = pages.view();
        Response.ResponseBuilder response;
        if (format.equals("json")) {
            response = Response.ok(json(shown, v).getBytes(StandardCharsets.UTF_8)).header("Content-Type", "application/json; charset=utf-8");
            if (Formats.of(request).getFirst().equals("json")) {
                long pageCount = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
                response.header("x-total-count", matches.size());
                if (page != pageCount) response.header("link", "<" + withPage(request.requestUrl(), page + 1) + ">; rel=\"next\"");
            }
        } else {
            response = Responses.html(200, Pages.bytes(v, (out, view) -> JteindexGenerated.render(out, null, view, shown)));
        }
        if (Formats.variesOnAccept(request)) response.header("Vary", "Accept");
        return response.build();
    }

    private long pageParam() {
        String page = request.params().string("page");
        return page == null ? 0 : RubyStrings.toI(page);
    }

    /// Names HTML-escaped, avatar URLs absolute.
    private static String json(List<UserPages.MentionUser> shown, View v) {
        var list = new ArrayList<Map<String, Object>>();
        for (var mention : shown) {
            var user = new LinkedHashMap<String, Object>();
            user.put("name", Html.escape(mention.user().name()));
            user.put("value", mention.user().id());
            user.put("avatar_url", v.baseUrl + v.avatarPath(mention.user()));
            user.put("sgid", mention.attachableSgid());
            list.add(user);
        }
        return Json.encode(list);
    }

    /// The URL with `page` set: query parameters decoded, sorted by name and encoded again.
    static String withPage(String url, long page) {
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? null : url.substring(hash + 1);
        String base = hash < 0 ? url : url.substring(0, hash);
        int question = base.indexOf('?');
        String path = question < 0 ? base : base.substring(0, question);
        var values = new TreeMap<String, @Nullable String>();
        if (question >= 0) {
            for (String pair : base.substring(question + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                if (eq < 0) values.put(decode(pair), null);
                else values.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1).replace('+', ' ')));
            }
        }
        values.put("page", Long.toString(page));
        var query = new ArrayList<String>();
        values.forEach((key, value) -> query.add(value == null ? encode(key) : encode(key) + "=" + encode(value)));
        return path + "?" + String.join("&", query) + (fragment == null ? "" : "#" + fragment);
    }

    private static String decode(String component) {
        return URLDecoder.decode(component.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /// Everything but unreserved characters percent-encoded (`ERB::Util.url_encode`).
    private static String encode(String component) {
        var out = new StringBuilder();
        for (byte b : component.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') out.append((char) c);
            else out.append('%').append(String.format("%02X", c));
        }
        return out.toString();
    }
}
