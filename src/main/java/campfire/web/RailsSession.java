package campfire.web;

import campfire.rails.Secrets;
import campfire.rails.SecureRandoms;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// The encrypted Rails session in the `_campfire_session` cookie, with its flash, so sessions
/// Rails wrote stay valid and Rails can read these. Decrypted on first use; `ResponseFinisher`
/// writes it back when it changed.
@RequestScoped
public class RailsSession {
    public static final String COOKIE = "_campfire_session";

    @Inject
    Cookies cookies;
    @Inject
    Secrets secrets;
    @Inject
    RoutingContext routing;

    private @Nullable Map<String, Object> values;
    private boolean dirty;
    private @Nullable Flash flash;

    @PostConstruct
    void register() {
        RequestState.of(routing).session = this;
    }

    /// Empty when the cookie is missing or tampered with.
    @SuppressWarnings("unchecked")
    private Map<String, Object> values() {
        if (values == null) {
            Object decrypted = secrets.decryptCookie(COOKIE, cookies.get(COOKIE), Instant.now());
            values = decrypted instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        }
        return values;
    }

    public @Nullable Object get(String key) {
        return values().get(key);
    }

    public void set(String key, @Nullable Object value) {
        if (Objects.equals(values().get(key), value)) return;
        if (value == null) values().remove(key);
        else values().put(key, value);
        dirty = true;
    }

    /// Starts an empty session, flash included (sign-in and sign-out do this against fixation).
    public void reset() {
        values = new LinkedHashMap<>();
        dirty = true;
        flash = null;
    }

    public Flash flash() {
        if (flash == null) flash = new Flash(values().get("flash"));
        return flash;
    }

    /// Forgets changes to the session; see {@link RequestState#discardChanges}.
    void discardChanges() {
        flash = null;
        dirty = false;
    }

    /// Re-encrypts the session cookie when it changed, and deletes it once nothing but the
    /// session id is left in it. Called once, after the resource has run.
    void commit() {
        if (flash != null) {
            Object value = flash.toSessionValue();
            if (value != null) set("flash", value);
            else if (values().containsKey("flash")) set("flash", null);
        }
        if (!dirty) return;
        Map<String, Object> session = values();
        var values = new LinkedHashMap<>(session);
        values.remove("session_id");
        // Rails keeps its CSRF token here; the app checks Fetch Metadata instead and never reads it.
        values.remove("_csrf_token");
        if (values.isEmpty()) {
            cookies.delete(COOKIE);
        } else {
            Instant expires = Secrets.permanentExpiresAt(Instant.now());
            if (!session.containsKey("session_id")) session.put("session_id", SecureRandoms.hex(16));
            cookies.set(COOKIE, secrets.encryptCookie(COOKIE, session, expires), expires, true, CookieSameSite.LAX);
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
}
