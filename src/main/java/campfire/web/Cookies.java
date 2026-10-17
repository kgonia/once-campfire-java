package campfire.web;

import campfire.rails.HttpFormats;
import campfire.rails.Secrets;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/// The request's cookies, and the ones the response sets. Values are written escaped and read
/// unescaped as Rack does, so cookies Rails set stay readable and Rails can read these. Cookies
/// set here are added to the response by `ResponseFinisher` after the resource returns.
@RequestScoped
public class Cookies {
    public static final String SESSION_TOKEN = "session_token";

    @Inject
    RoutingContext routing;
    @Inject
    RequestOrigin origin;
    @Inject
    Secrets secrets;

    /// By name; a deleted cookie maps to null.
    private final Map<String, @Nullable Cookie> outgoing = new LinkedHashMap<>();

    @PostConstruct
    void register() {
        RequestState.of(routing).cookies = this;
    }

    /// The value the request came with, or the one set since.
    public @Nullable String get(String name) {
        if (outgoing.containsKey(name)) {
            Cookie set = outgoing.get(name);
            return set == null ? null : HttpFormats.unescape(set.getValue());
        }
        return incoming(routing.request(), name);
    }

    public static @Nullable String incoming(HttpServerRequest request, String name) {
        Cookie cookie = request.getCookie(HttpFormats.escape(name));
        return cookie == null ? null : HttpFormats.unescape(cookie.getValue());
    }

    /// Verified values are remembered process-wide: a valid signature stays valid, and permanent
    /// cookies don't expire for twenty years.
    private static final ConcurrentHashMap<String, String> VERIFIED = new ConcurrentHashMap<>();

    /// The value of a cookie signed with the app's secret, or null when missing or forged.
    public @Nullable String signed(String name) {
        return verify(secrets, name, get(name));
    }

    public static @Nullable String verify(Secrets secrets, String name, @Nullable String raw) {
        if (raw == null) return null;
        String key = name + "=" + raw;
        String cached = VERIFIED.get(key);
        if (cached != null) return cached;
        String value = secrets.verifySignedCookie(name, raw, Instant.now());
        if (value != null) {
            if (VERIFIED.size() > 10_000) VERIFIED.clear();
            VERIFIED.put(key, value);
        }
        return value;
    }

    /// `secure` over HTTPS; `sameSite` null is Rails' default, `lax`. A later call for the same name
    /// replaces it.
    public void set(String name, String value, @Nullable Instant expires, boolean httpOnly, @Nullable CookieSameSite sameSite) {
        Cookie cookie = Cookie.cookie(HttpFormats.escape(name), HttpFormats.escape(value)).setPath("/").setSecure(origin.ssl())
            .setHttpOnly(httpOnly).setSameSite(sameSite == null ? CookieSameSite.LAX : sameSite);
        if (expires != null) cookie.setMaxAge(Math.max(0, Duration.between(Instant.now(), expires).toSeconds()));
        outgoing.put(name, cookie);
    }

    /// Signed, httponly and `SameSite=Lax`, expiring in twenty years.
    public void setSignedPermanent(String name, String value) {
        Instant expires = Secrets.permanentExpiresAt(Instant.now());
        set(name, secrets.signCookie(name, value, expires), expires, true, CookieSameSite.LAX);
    }

    public void setPermanent(String name, String value) {
        set(name, value, Secrets.permanentExpiresAt(Instant.now()), false, null);
    }

    /// Only for cookies the request came with: deleting an absent cookie sends nothing.
    public void delete(String name) {
        if (get(name) == null) return;
        outgoing.put(name, null);
    }

    /// Forgets the cookies set so far.
    void discard() {
        outgoing.clear();
    }

    /// Adds the cookies set while serving the request to the response.
    void writeTo(HttpServerResponse response) {
        outgoing.forEach((name, cookie) -> response.addCookie(cookie != null ? cookie
            : Cookie.cookie(HttpFormats.escape(name), "").setPath("/").setMaxAge(0).setSameSite(CookieSameSite.LAX)));
    }
}
