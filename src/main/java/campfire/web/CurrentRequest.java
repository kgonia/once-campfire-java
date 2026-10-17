package campfire.web;

import campfire.App;
import campfire.sessions.Session;
import campfire.users.User;
import campfire.rails.HttpFormats;
import campfire.rails.Json;
import campfire.rails.SecureRandoms;
import campfire.rails.Secrets;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/// The request being served: its parameters (query, form and path merged, Rails style), cookies,
/// the encrypted Rails session with its flash, and who is signed in. Cookies set here are written
/// by `ResponseFinisher` after the resource returns.
@RequestScoped
public class CurrentRequest {

    @Inject
    App app;
    @Inject
    RoutingContext routing;
    @Inject
    RequestOrigin origin;
    @Inject
    Cookies cookies;
    @Inject
    RailsSession railsSession;
    @Inject
    campfire.sessions.Viewer viewer;

    /// What the Vert.x request says, read on first use.
    private record Facts(String method, String path, @Nullable String query, Params params, @Nullable Map<String, Object> jsonBody) {}

    private @Nullable Facts facts;
    /// Every header keyed in lower case, copied only for {@link #headers()}; single headers are read
    /// from Vert.x's own map, which ignores case.
    private @Nullable Map<String, List<String>> headers;
    /// The body claimed to be JSON but isn't; the request is a 400 before anything else.
    private boolean malformedBody;



    /// The method is the overridden one when a form sent `_method` (the router re-routes those).
    private Facts facts() {
        if (facts != null) return facts;
        var http = routing.request();
        String method = http.method().name();
        var params = new Params();
        params.addQuery(http.query());
        MultiMap form = http.isExpectMultipart() || method.equals("POST") || method.equals("PUT") || method.equals("PATCH")
            || method.equals("DELETE") ? http.formAttributes() : null;
        if (form != null) for (var entry : form) params.put(entry.getKey(), entry.getValue());
        for (FileUpload upload : routing.fileUploads()) {
            if (upload.fileName() == null || upload.fileName().isEmpty()) continue;
            params.put(upload.name(), new Params.Upload(upload.fileName(), upload.contentType(), Path.of(upload.uploadedFileName()), upload.size()));
        }
        String contentType = http.getHeader("content-type");
        Map<String, Object> jsonBody = contentType == null ? null : jsonBody(contentType);
        if (jsonBody != null) jsonBody.forEach(params::putValue);
        facts = new Facts(method, Objects.requireNonNullElse(http.path(), "/"), http.query(), params, jsonBody);
        return facts;
    }

    /// A JSON body's parameters, merged into the params as they are. A body that isn't an object
    /// is the `_json` parameter; malformed JSON leaves none (see {@link #malformedBody}). Null for
    /// other content types.
    private @Nullable Map<String, Object> jsonBody(String contentType) {
        String mime = contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!mime.equals("application/json") && !mime.equals("text/x-json") && !mime.equals("application/jsonrequest")) return null;
        var body = new LinkedHashMap<String, Object>();
        String text = new String(body(), java.nio.charset.StandardCharsets.UTF_8);
        if (text.isBlank()) return body;
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.ParseException e) {
            malformedBody = true;
            return body;
        }
        if (parsed instanceof Map<?, ?> map) map.forEach((key, value) -> body.put(String.valueOf(key), value));
        else body.put("_json", parsed);
        return body;
    }

    /// Nests a JSON body's parameters under `key` too, as Rails' `wrap_parameters` does for the
    /// controller's model, unless the request already sent a `key` parameter.
    public void wrapJsonParameters(String key) {
        Map<String, Object> body = facts().jsonBody();
        if (body == null || params().has(key)) return;
        var wrapped = new LinkedHashMap<String, Object>(body);
        wrapped.keySet().removeAll(List.of("authenticity_token", "_method", "utf8"));
        params().putValue(key, wrapped);
    }

    /// Path parameters join the query and form parameters, as Rails merges them.
    void addPathParams(Map<String, String> pathParams) {
        pathParams.forEach(params()::put);
    }

    RequestState state() {
        return RequestState.of(routing);
    }

    /// For routes with a format of their own (the bot API's is JSON), unless the path names one.
    public void defaultFormat(String format) {
        RequestState state = state();
        if (state.format == null) state.format = format;
    }

    /// See {@link RequestState#dataVersionAtStart}.
    public long dataVersionAtStart() {
        return state().dataVersionAtStart;
    }

    // -- Request -----------------------------------------------------------------------------------

    public App app() {
        return app;
    }

    public String method() {
        return facts().method();
    }

    public String path() {
        return facts().path();
    }

    public @Nullable String query() {
        return facts().query();
    }

    public Map<String, List<String>> headers() {
        if (headers == null) {
            var copy = new LinkedHashMap<String, List<String>>();
            for (var entry : routing.request().headers()) {
                copy.computeIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), k -> new ArrayList<>(1)).add(entry.getValue());
            }
            headers = copy;
        }
        return headers;
    }

    public Params params() {
        return facts().params();
    }

    /// The `.json`/`.js` suffix the path came with (or the route's default), or null.
    public @Nullable String format() {
        return state().format;
    }

    public byte[] body() {
        var body = routing.body();
        return body == null || body.buffer() == null ? new byte[0] : body.buffer().getBytes();
    }

    public boolean isGet() {
        return method().equals("GET") || method().equals("HEAD");
    }

    public boolean isHead() {
        return method().equals("HEAD");
    }

    public @Nullable String header(String name) {
        return routing.request().getHeader(name);
    }

    public @Nullable String userAgent() {
        return header("user-agent");
    }

    public boolean ssl() {
        return origin.ssl();
    }

    public String protocol() {
        return origin.protocol();
    }

    public String host() {
        return origin.host();
    }

    public String baseUrl() {
        return origin.baseUrl();
    }

    public String url(String path) {
        return origin.url(path);
    }

    public String requestUrl() {
        return origin.requestUrl();
    }

    public String remoteIp() {
        return origin.remoteIp();
    }

    public boolean wantsJson() {
        String format = format();
        if ("json".equals(format)) return true;
        String accept = header("accept");
        return format == null && accept != null && accept.startsWith("application/json");
    }

    public boolean isTurboFrame() {
        return header("turbo-frame") != null;
    }

    // -- Who is signed in -----------------------------------------------------------------------------

    public User user() {
        return viewer.user();
    }

    public @Nullable User userIfSignedIn() {
        return viewer.userIfSignedIn();
    }

    public @Nullable Session session() {
        return viewer.session();
    }

    public boolean authenticatedByBot() {
        return viewer.authenticatedByBot();
    }


    // -- Cookies and the Rails session ---------------------------------------------------------------

    public @Nullable String cookie(String name) {
        return cookies.get(name);
    }

    public @Nullable String signedCookie(String name) {
        return cookies.signed(name);
    }

    public void setSignedPermanentCookie(String name, String value) {
        cookies.setSignedPermanent(name, value);
    }

    public void setPermanentCookie(String name, String value) {
        cookies.setPermanent(name, value);
    }

    public void deleteCookie(String name) {
        cookies.delete(name);
    }

    public @Nullable Object sessionValue(String key) {
        return railsSession.get(key);
    }

    public void setSessionValue(String key, @Nullable Object value) {
        railsSession.set(key, value);
    }

    public void resetSession() {
        railsSession.reset();
    }

    public RailsSession.Flash flash() {
        return railsSession.flash();
    }

    void discardChanges() {
        railsSession.discardChanges();
    }

    // -- Params ------------------------------------------------------------------------------------

    /// The 400 error page unless `key` is present (strings, arrays and hashes alike).
    public void require(String key) {
        if (params().get(key) == null) throw new HttpError(400);
    }

    /// The 400 error page unless `key` is a nested hash (`message[body]=...`) with something in it.
    public void requireHash(String key) {
        if (!(params().get(key) instanceof Map<?, ?> map) || map.isEmpty()) throw new HttpError(400);
    }

    boolean malformedBody() {
        facts();
        return malformedBody;
    }
}
