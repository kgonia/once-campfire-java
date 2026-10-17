package campfire.web;

import campfire.sessions.Session;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.ws.rs.core.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/// Whole private pages for the hot reads (room, messages page, sidebar, search), per user, session
/// and request headers. Resources look up only after authentication and their own access checks.
/// Entries belong to the SQLite data version seen when the request arrived, and both lookup and
/// admission re-check it, so a commit by anyone (this process or another) during authentication or
/// rendering can never let an old render serve the new version.
public final class ResponseCache {
    private static final int MAX_BODY = 1024 * 1024;
    private static final int MAX_KEY_INPUT = 8192;
    /// Relative dates and expiring links still refresh without a commit.
    private static final Duration TTL = Duration.ofSeconds(15);

    record Entry(long version, int status, Map<String, List<String>> headers, byte[] body, byte @Nullable [] gzip) {}

    /// What a miss remembers so the finished response can be admitted.
    record Ticket(long version, String key) {}

    private final Cache<String, Entry> entries;
    private final boolean enabled;

    public ResponseCache(long capacityBytes) {
        this.enabled = capacityBytes > 0;
        this.entries = Caffeine.newBuilder()
            .maximumWeight(Math.max(1, capacityBytes))
            .weigher((String key, Entry e) -> key.length() + e.body().length + (e.gzip() == null ? 0 : e.gzip().length) + 256)
            .expireAfterWrite(TTL)
            .build();
    }

    /// The cached response for this request, or empty; on a miss the response the resource then
    /// returns is offered to {@link #admit}. Bots, flashes and anything but plain GETs never cache.
    public Optional<Response> lookup(CurrentRequest request, String endpoint) {
        Session session = request.session();
        if (!enabled || request.dataVersionAtStart() < 0 || session == null || request.authenticatedByBot() || !request.flash().isEmpty()
            || !eligible(request)) {
            return Optional.empty();
        }
        String key = key(request, session, endpoint);
        if (key == null) return Optional.empty();
        long version = request.app().db().dataVersion();
        if (version != request.dataVersionAtStart()) return Optional.empty();
        Entry entry = entries.getIfPresent(key);
        if (entry == null || entry.version() != version) {
            request.cacheTicket(new Ticket(version, key));
            return Optional.empty();
        }
        var response = Response.status(entry.status()).entity(entry.body());
        entry.headers().forEach((name, values) -> values.forEach(value -> response.header(name, value)));
        request.cachedGzip(entry.gzip());
        return Optional.of(response.build());
    }

    /// Keeps a finished 200 (headers and ETag final, before gzip) if nothing was committed since the
    /// version its render started from.
    void admit(CurrentRequest request, int status, Map<String, List<String>> headers, byte[] body, byte @Nullable [] gzip) {
        Ticket ticket = request.cacheTicket();
        if (ticket == null || status != 200 || body.length > MAX_BODY || !request.flash().isEmpty()) return;
        String cacheControl = first(headers, "Cache-Control");
        if (cacheControl != null && (cacheControl.contains("no-store") || cacheControl.contains("no-transform"))) return;
        if (request.app().db().dataVersion() != ticket.version()) return;
        var kept = new LinkedHashMap<String, List<String>>();
        headers.forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Set-Cookie") && !name.equalsIgnoreCase("Date")) kept.put(name, List.copyOf(values));
        });
        entries.put(ticket.key(), new Entry(ticket.version(), status, kept, body, gzip));
    }

    static @Nullable String first(Map<String, List<String>> headers, String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().getFirst();
        }
        return null;
    }

    private static boolean eligible(CurrentRequest request) {
        if (!request.method().equals("GET") && !request.method().equals("HEAD")) return false;
        if (request.header("range") != null || request.header("upgrade") != null) return false;
        String cacheControl = request.header("cache-control");
        if (cacheControl != null && (cacheControl.contains("no-cache") || cacheControl.contains("no-store"))) return false;
        String pragma = request.header("pragma");
        return pragma == null || !pragma.equalsIgnoreCase("no-cache");
    }

    /// Everything a page may vary on: data version, endpoint, user, session, URL and every request
    /// header except validators and tracing ids. Null when that's implausibly large.
    private static @Nullable String key(CurrentRequest request, Session session, String endpoint) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        int[] length = {0};
        java.util.function.Consumer<String> field = value -> {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            length[0] += bytes.length + 8;
            digest.update(ByteBuffer.allocate(8).putLong(bytes.length).array());
            digest.update(bytes);
        };
        field.accept(Long.toString(request.dataVersionAtStart()));
        field.accept(endpoint);
        field.accept(Long.toString(request.user().id()));
        field.accept(Long.toString(session.id()));
        String query = request.query();
        field.accept(request.path() + (query == null ? "" : "?" + query));
        field.accept(request.baseUrl());
        var headers = new TreeMap<>(request.headers());
        for (var header : headers.entrySet()) {
            String name = header.getKey();
            if (name.equals("if-none-match") || name.equals("if-modified-since") || name.equals("x-request-id") || name.equals("x-request-start")) {
                continue;
            }
            field.accept(name);
            for (String value : header.getValue()) field.accept(value);
            field.accept("");
        }
        return length[0] > MAX_KEY_INPUT ? null : HexFormat.of().formatHex(digest.digest());
    }
}
