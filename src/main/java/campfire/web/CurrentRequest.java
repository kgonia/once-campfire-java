package campfire.web;

import campfire.App;
import campfire.users.Ban;
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
import jakarta.ws.rs.core.Response;
import java.net.InetAddress;
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
    public static final String SESSION_COOKIE = "_campfire_session";
    public static final String SESSION_TOKEN_COOKIE = "session_token";

    @Inject
    App app;
    @Inject
    RoutingContext routing;

    /// What the Vert.x request says, read on first use; `cookies` also reflects the cookies set
    /// while serving the request.
    private record Facts(String method, String path, @Nullable String query, @Nullable String peerAddress, Params params,
                         Map<String, String> cookies, @Nullable Map<String, Object> jsonBody) {}

    private @Nullable Facts facts;
    /// Every header keyed in lower case, copied only for {@link #headers()}; single headers are read
    /// from Vert.x's own map, which ignores case.
    private @Nullable Map<String, List<String>> headers;
    /// `ssl()` and `baseUrl()`, which links on a page ask for over and over.
    private @Nullable Boolean ssl;
    private @Nullable String baseUrl;
    /// The body claimed to be JSON but isn't; the request is a 400 before anything else.
    private boolean malformedBody;
    private final List<String> setCookies = new ArrayList<>();
    private @Nullable String format;

    private @Nullable User user;
    private @Nullable Session session;
    private boolean authenticatedByBot;
    /// The database's data version when the access filter admitted the request, read once and
    /// shared by the session lookup and `ResponseCache`; -1 for framework endpoints.
    private long dataVersionAtStart = -1;
    private ResponseCache.@Nullable Ticket cacheTicket;
    /// The gzipped body that came with a cache hit, so it isn't compressed again.
    private byte @Nullable [] cachedGzip;
    /// File downloads skip the page security headers and the content ETag, as Rails' streamed
    /// responses do.
    private boolean download;
    private boolean framework;
    private boolean exceptionResponse;

    private @Nullable Map<String, Object> railsSession;
    private boolean railsSessionDirty;
    private @Nullable Flash flash;
    private @Nullable String remoteIp;

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
        var address = http.remoteAddress();
        facts = new Facts(method, Objects.requireNonNullElse(http.path(), "/"), http.query(), address == null ? null : address.hostAddress(), params,
            parseCookies(http.getHeader("cookie")), jsonBody);
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

    void format(String format) {
        this.format = format;
    }

    /// For routes with a format of their own (the bot API's is JSON), unless the path names one.
    public void defaultFormat(String format) {
        if (this.format == null) this.format = format;
    }

    // The request's bookkeeping is reached through methods: resources hold a client proxy, whose own
    // fields would be shared by every request.

    RoutingContext routing() {
        return routing;
    }

    public long dataVersionAtStart() {
        return dataVersionAtStart;
    }

    void dataVersionAtStart(long version) {
        this.dataVersionAtStart = version;
    }

    ResponseCache.@Nullable Ticket cacheTicket() {
        return cacheTicket;
    }

    void cacheTicket(ResponseCache.Ticket ticket) {
        this.cacheTicket = ticket;
    }

    byte @Nullable [] cachedGzip() {
        return cachedGzip;
    }

    void cachedGzip(byte @Nullable [] gzip) {
        this.cachedGzip = gzip;
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

    /// The `.json`/`.js` suffix the path came with, or null.
    public @Nullable String format() {
        return format;
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

    /// Every request counts as HTTPS unless `DISABLE_SSL` is set; then a trusted proxy's
    /// `X-Forwarded-Proto` decides.
    public boolean ssl() {
        if (ssl == null) {
            String proto = !app.config().disableSsl() ? null : trustedForwarded() ? header("x-forwarded-proto") : null;
            ssl = !app.config().disableSsl() || proto != null && proto.equalsIgnoreCase("https");
        }
        return ssl;
    }

    public String protocol() {
        return ssl() ? "https://" : "http://";
    }

    /// The Host (or a trusted proxy's X-Forwarded-Host) without the scheme's default port.
    public String hostWithPort() {
        String forwardedHost = trustedForwarded() ? header("x-forwarded-host") : null;
        String host = forwardedHost != null ? forwardedHost : header("host");
        if (host == null || host.isBlank()) host = "localhost";
        host = host.split(",")[0].strip();
        if (ssl() && host.endsWith(":443")) host = host.substring(0, host.length() - 4);
        if (!ssl() && host.endsWith(":80")) host = host.substring(0, host.length() - 3);
        return host;
    }

    public String host() {
        String h = hostWithPort();
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close > 0 ? h.substring(0, close + 1) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon > 0 ? h.substring(0, colon) : h;
    }

    public String baseUrl() {
        if (baseUrl == null) baseUrl = protocol() + hostWithPort();
        return baseUrl;
    }

    public String url(String path) {
        return baseUrl() + path;
    }

    public String requestUrl() {
        String query = query();
        return baseUrl() + path() + (query == null || query.isEmpty() ? "" : "?" + query);
    }

    private boolean trustedForwarded() {
        return isTrustedProxy(facts().peerAddress());
    }

    /// Loopback, private, unique-local and link-local addresses: where a reverse proxy may sit.
    static boolean isTrustedProxy(@Nullable String address) {
        InetAddress ip = Ban.literal(address);
        return ip != null && (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress() || Ban.isUniqueLocal(ip));
    }

    /// The client's address: the last untrusted hop of a trusted proxy's `X-Forwarded-For`, else
    /// the peer. Bans and rate limits key on it.
    public String remoteIp() {
        if (remoteIp != null) return remoteIp;
        String peerAddress = facts().peerAddress();
        String forwarded = header("x-forwarded-for");
        if (forwarded != null && isTrustedProxy(peerAddress)) {
            String[] chain = forwarded.split(",");
            for (int i = chain.length - 1; i >= 0; i--) {
                String candidate = chain[i].strip();
                if (!candidate.isEmpty() && !isTrustedProxy(candidate)) return remoteIp = candidate;
            }
            if (chain.length > 0 && !chain[0].isBlank()) return remoteIp = chain[0].strip();
        }
        return remoteIp = peerAddress == null ? "" : peerAddress;
    }

    public boolean wantsTurboStream() {
        String accept = header("accept");
        return accept != null && accept.contains("text/vnd.turbo-stream.html");
    }

    public boolean wantsJson() {
        if ("json".equals(format)) return true;
        String accept = header("accept");
        return format == null && accept != null && accept.startsWith("application/json");
    }

    public boolean isTurboFrame() {
        return header("turbo-frame") != null;
    }

    public void markDownload() {
        download = true;
    }

    boolean isDownload() {
        return download;
    }

    /// Served by one of Rails' own controllers (Active Storage, Turbo Native) rather than the
    /// app's: none of the app's checks or version headers apply.
    void markFramework() {
        framework = true;
    }

    boolean isFramework() {
        return framework;
    }

    /// The response is one of Rails' exception pages, which bypass the controller: no session
    /// commit, cookies, default or version headers, ETag or Cache-Control.
    public void markExceptionResponse() {
        exceptionResponse = true;
    }

    boolean isExceptionResponse() {
        return exceptionResponse;
    }

    // -- Who is signed in -----------------------------------------------------------------------------

    /// The signed-in user (or bot). Only for resources behind sign-in, which is all but the
    /// {@link Access.Public} and {@link Access.GuestOnly} ones.
    public User user() {
        if (user == null) throw new IllegalStateException("not signed in");
        return user;
    }

    public @Nullable User userIfSignedIn() {
        return user;
    }

    /// The browser session; null for guests and bots.
    public @Nullable Session session() {
        return session;
    }

    public boolean authenticatedByBot() {
        return authenticatedByBot;
    }

    public void signIn(Session session, User user) {
        this.session = session;
        this.user = user;
        this.authenticatedByBot = false;
    }

    public void signInBot(User bot) {
        this.session = null;
        this.user = bot;
        this.authenticatedByBot = true;
    }

    public void signOut() {
        this.session = null;
        this.user = null;
    }

    // -- Cookies -----------------------------------------------------------------------------------

    /// Rack 3's cookie header parsing: split on `; `, names kept raw, values unescaped (raw when the
    /// escape is malformed), quotes left alone; the first of a repeated name wins.
    static Map<String, String> parseCookies(@Nullable String header) {
        var cookies = new LinkedHashMap<String, String>();
        if (header == null) return cookies;
        int start = 0;
        while (start < header.length()) {
            int semicolon = header.indexOf(';', start);
            int end = semicolon < 0 ? header.length() : semicolon;
            String part = header.substring(start, end);
            start = end + 1;
            while (semicolon >= 0 && start < header.length() && header.charAt(start) == ' ') start++;
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String name = eq < 0 ? part : part.substring(0, eq);
            if (!cookies.containsKey(name)) cookies.put(name, eq < 0 ? "" : HttpFormats.unescape(part.substring(eq + 1)));
        }
        return cookies;
    }

    public @Nullable String cookie(String name) {
        return facts().cookies().get(name);
    }

    /// Verified values are remembered process-wide: a valid signature stays valid, and permanent
    /// cookies don't expire for twenty years.
    private static final ConcurrentHashMap<String, String> VERIFIED = new ConcurrentHashMap<>();

    /// The value of a cookie signed with the app's secret, or null when missing or forged.
    public @Nullable String signedCookie(String name) {
        String raw = cookie(name);
        if (raw == null) return null;
        String key = name + "=" + raw;
        String cached = VERIFIED.get(key);
        if (cached != null) return cached;
        String value = app.secrets().verifySignedCookie(name, raw, Instant.now());
        if (value != null) {
            if (VERIFIED.size() > 10_000) VERIFIED.clear();
            VERIFIED.put(key, value);
        }
        return value;
    }

    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC);

    /// RFC 7231 dates with a two-digit day, as Rack writes them (`RFC_1123_DATE_TIME` doesn't pad).
    public static String httpDate(Instant time) {
        return HTTP_DATE.format(time);
    }

    /// Queues a `Set-Cookie` in Rack's format; a later call for the same name replaces it.
    /// `sameSite` null is Rails' default, `lax`.
    public void setCookie(String name, String value, @Nullable Instant expires, boolean httpOnly, @Nullable String sameSite) {
        var out = new StringBuilder(HttpFormats.escape(name)).append('=').append(HttpFormats.escape(value)).append("; path=/");
        if (expires != null) out.append("; expires=").append(httpDate(expires));
        if (ssl()) out.append("; secure");
        if (httpOnly) out.append("; httponly");
        out.append("; samesite=").append(sameSite == null ? "lax" : sameSite);
        setCookies.removeIf(c -> c.startsWith(HttpFormats.escape(name) + "="));
        setCookies.add(out.toString());
        facts().cookies().put(name, value);
    }

    /// Signed, httponly and `SameSite=Lax`, expiring in twenty years.
    public void setSignedPermanentCookie(String name, String value) {
        Instant expires = Secrets.permanentExpiresAt(Instant.now());
        setCookie(name, app.secrets().signCookie(name, value, expires), expires, true, "lax");
    }

    public void setPermanentCookie(String name, String value) {
        setCookie(name, value, Secrets.permanentExpiresAt(Instant.now()), false, null);
    }

    /// Only for cookies the request came with: deleting an absent cookie sends nothing.
    public void deleteCookie(String name) {
        Map<String, String> cookies = facts().cookies();
        if (cookies.get(name) == null && !cookies.containsKey(name)) return;
        setCookies.removeIf(c -> c.startsWith(HttpFormats.escape(name) + "="));
        setCookies.add(HttpFormats.escape(name) + "=; path=/; max-age=0; expires=Thu, 01 Jan 1970 00:00:00 GMT; samesite=lax");
        cookies.remove(name);
    }

    List<String> setCookies() {
        return setCookies;
    }

    // -- Rails session and flash -------------------------------------------------------------------

    /// The `_campfire_session` cookie's contents, decrypted on first use; empty when missing or
    /// tampered with.
    @SuppressWarnings("unchecked")
    private Map<String, Object> railsSession() {
        if (railsSession == null) {
            Object decrypted = app.secrets().decryptCookie(SESSION_COOKIE, cookie(SESSION_COOKIE), Instant.now());
            railsSession = decrypted instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        }
        return railsSession;
    }

    public @Nullable Object sessionValue(String key) {
        return railsSession().get(key);
    }

    public void setSessionValue(String key, @Nullable Object value) {
        if (Objects.equals(railsSession().get(key), value)) return;
        if (value == null) railsSession().remove(key);
        else railsSession().put(key, value);
        railsSessionDirty = true;
    }

    /// Starts an empty session, flash included (sign-in and sign-out do this against fixation).
    public void resetSession() {
        railsSession = new LinkedHashMap<>();
        railsSessionDirty = true;
        flash = null;
    }

    public Flash flash() {
        if (flash == null) flash = new Flash(railsSession().get("flash"));
        return flash;
    }

    /// Re-encrypts the session cookie when it changed, and deletes it once nothing but the
    /// session id is left in it. Called once, after the resource has run.
    void commitSession() {
        if (flash != null) {
            Object value = flash.toSessionValue();
            if (value == null) {
                if (railsSession().containsKey("flash")) setSessionValue("flash", null);
            } else {
                setSessionValue("flash", value);
            }
        }
        if (!railsSessionDirty) return;
        Map<String, Object> railsSession = railsSession();
        var values = new LinkedHashMap<>(railsSession);
        values.remove("session_id");
        values.remove("_csrf_token");
        if (values.isEmpty()) {
            deleteCookie(SESSION_COOKIE);
        } else {
            Instant expires = Secrets.permanentExpiresAt(Instant.now());
            if (!railsSession.containsKey("session_id")) railsSession.put("session_id", SecureRandoms.hex(16));
            setCookie(SESSION_COOKIE, app.secrets().encryptCookie(SESSION_COOKIE, railsSession, expires), expires, true, "lax");
        }
    }

    /// Messages for the next page: values that came with the request are shown now and then
    /// dropped; `set` values go to the next request; `now` values only to this one.
    public static final class Flash {
        private final Map<String, String> incoming = new LinkedHashMap<>();
        private final Map<String, String> next = new LinkedHashMap<>();
        private final Map<String, String> now = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        Flash(@Nullable Object sessionValue) {
            if (sessionValue instanceof Map<?, ?> m && m.get("flashes") instanceof Map<?, ?> flashes) {
                var discard = m.get("discard") instanceof List<?> l ? l : List.of();
                for (var entry : ((Map<String, Object>) flashes).entrySet()) {
                    if (!discard.contains(entry.getKey()) && entry.getValue() != null) incoming.put(entry.getKey(), entry.getValue().toString());
                }
            }
        }

        public boolean isEmpty() {
            return incoming.isEmpty() && next.isEmpty() && now.isEmpty();
        }

        public @Nullable String get(String key) {
            if (now.containsKey(key)) return now.get(key);
            if (next.containsKey(key)) return next.get(key);
            return incoming.get(key);
        }

        public void set(String key, String value) {
            next.put(key, value);
        }

        public void now(String key, String value) {
            now.put(key, value);
        }

        /// The session's `flash` entry in Rails' shape, or null when nothing carries over.
        @Nullable Object toSessionValue() {
            if (next.isEmpty()) return null;
            var value = new LinkedHashMap<String, Object>();
            value.put("discard", List.of());
            value.put("flashes", new LinkedHashMap<>(next));
            return value;
        }
    }

    // -- Responses ---------------------------------------------------------------------------------

    /// An empty response. Rails sends `text/html` for these even when the request asked for JSON.
    public Response head(int status) {
        return Response.status(status).entity(new byte[0]).header("Content-Type", "text/html").build();
    }

    /// A redirect with Rails' little HTML body; paths are made absolute against this request.
    public Response redirect(String location) {
        return redirect(location, 302);
    }

    public Response redirect(String location, int status) {
        String absolute = location.startsWith("/") ? baseUrl() + location : location;
        return html(status, new byte[0]).header("Location", absolute).build();
    }

    public Response redirectWithNotice(String location, String notice) {
        flash().set("notice", notice);
        return redirect(location);
    }

    public Response redirectWithAlert(String location, String alert) {
        flash().set("alert", alert);
        return redirect(location);
    }

    public static Response.ResponseBuilder html(int status, String html) {
        return html(status, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static Response.ResponseBuilder html(int status, byte[] html) {
        return Response.status(status).entity(html).header("Content-Type", "text/html; charset=utf-8");
    }

    public static Response turboStream(byte[] body) {
        return Response.ok(body).header("Content-Type", "text/vnd.turbo-stream.html; charset=utf-8").build();
    }

    public static Response json(int status, Object value) {
        return Response.status(status).entity(Json.encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .header("Content-Type", "application/json; charset=utf-8").build();
    }

    // -- Params ------------------------------------------------------------------------------------

    /// An id parameter as Active Record casts it: leading digits, else null.
    public @Nullable Long idParam(String name) {
        return Params.parseId(params().string(name));
    }

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
