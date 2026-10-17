package campfire.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The proxy's shared cache of public responses (Thruster's `CacheHandler`). GET and HEAD responses
/// whose `Cache-Control` says `public` with a positive `s-max-age` (sic) or `max-age`, and not
/// `no-cache`, are kept until they expire and served again, with `X-Cache: hit`, to any request
/// with the same method, path, query, host and values of the headers the response `Vary`s on.
///
/// Entries are keyed the way Thruster keys them: by the request alone first, and only when the
/// entry found there varies on a header this request disagrees about, by the request plus those
/// headers. An entry's size counts its key and headers, so many long URIs can't hold more memory
/// than `cacheSize`.
@NullMarked
final class FrontCache {
    /// Longer URIs aren't cached at all; the app's cacheable URLs (assets, avatars, QR codes) are
    /// far shorter.
    static final int MAX_CACHEABLE_URI = 2048;
    /// An entry's bookkeeping, charged on top of its key, headers and body.
    private static final int ENTRY_OVERHEAD = 256;

    private static final Pattern PUBLIC = Pattern.compile("\\bpublic\\b");
    private static final Pattern NO_CACHE = Pattern.compile("\\bno-cache\\b");
    private static final Pattern S_MAX_AGE = Pattern.compile("\\bs-max-age=(\\d+)\\b");
    private static final Pattern MAX_AGE = Pattern.compile("\\bmax-age=(\\d+)\\b");

    record Header(String name, String value) {}

    /// `variant` holds the request's values of the varying headers when the response was stored.
    record Stored(int status, List<Header> headers, byte[] body, List<Header> variant, Duration lifetime) {
        long size(String key) {
            long size = body.length + key.length() + ENTRY_OVERHEAD;
            for (Header h : headers) size += h.name().length() + h.value().length();
            for (Header h : variant) size += h.name().length() + h.value().length();
            return size;
        }

        @Nullable String first(String name) {
            for (Header h : headers) if (h.name().equalsIgnoreCase(name)) return h.value();
            return null;
        }
    }

    /// Where a missed request's response would be stored, and the headers it's matched on.
    record Miss(String key, Variant variant) {}

    sealed interface Lookup {}

    record Hit(Stored response) implements Lookup {}

    record Missed(Miss miss) implements Lookup {}

    private final long maxItemSize;
    private final long capacity;
    private final Cache<String, Stored> entries;

    FrontCache(long capacity, long maxItemSize) {
        this.capacity = capacity;
        this.maxItemSize = maxItemSize;
        this.entries = Caffeine.newBuilder()
            .maximumWeight(Math.max(0, capacity))
            .weigher((String key, Stored stored) -> (int) Math.min(Integer.MAX_VALUE, stored.size(key)))
            .expireAfter(new Expiry<String, Stored>() {
                @Override
                public long expireAfterCreate(String key, Stored stored, long now) {
                    return stored.lifetime().toNanos();
                }

                @Override
                public long expireAfterUpdate(String key, Stored stored, long now, long current) {
                    return stored.lifetime().toNanos();
                }

                @Override
                public long expireAfterRead(String key, Stored stored, long now, long current) {
                    return current;
                }
            })
            .build();
    }

    /// GET or HEAD, not a WebSocket upgrade or a range, and not an overlong URI. Thruster compares
    /// `Connection` and `Upgrade` exactly, case included.
    static boolean cacheable(HttpServerRequest request) {
        boolean method = request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD;
        boolean upgrade = "Upgrade".equals(request.getHeader("connection")) || "websocket".equals(request.getHeader("upgrade"));
        String range = request.getHeader("range");
        return method && !upgrade && (range == null || range.isEmpty()) && request.uri().length() <= MAX_CACHEABLE_URI;
    }

    Lookup lookup(HttpServerRequest request) {
        var variant = new Variant(request);
        String key = variant.key();
        Stored found = entries.getIfPresent(key);
        if (found != null) {
            variant.varyOn(found.first("vary"));
            if (!variant.matches(found.variant())) {
                key = variant.key();
                found = entries.getIfPresent(key);
            }
        }
        return found != null ? new Hit(found) : new Missed(new Miss(key, variant));
    }

    /// How long a response may be kept, or null when it mustn't be.
    static @Nullable Duration lifetime(int status, @Nullable String cacheControl, @Nullable String vary) {
        if (status < 200 || status > 399 || status == 304) return null;
        if (vary != null && vary.contains("*")) return null;
        String control = cacheControl == null ? "" : cacheControl;
        // `contains` first: almost every page is private, and that settles it without a regex.
        if (!control.contains("public") || !PUBLIC.matcher(control).find() || NO_CACHE.matcher(control).find()) return null;
        Matcher maxAge = S_MAX_AGE.matcher(control);
        if (!maxAge.find()) {
            maxAge = MAX_AGE.matcher(control);
            if (!maxAge.find()) return null;
        }
        try {
            long seconds = Long.parseLong(maxAge.group(1));
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /// Keeps a missed request's response, unless it's larger than an item may be.
    void store(Miss miss, int status, List<Header> headers, byte[] body, Duration lifetime) {
        miss.variant().varyOn(first(headers, "vary"));
        var stored = new Stored(status, List.copyOf(headers), body, miss.variant().headers(), lifetime);
        long size = stored.size(miss.key());
        if (size > maxItemSize || size > capacity) return;
        entries.put(miss.key(), stored);
    }

    /// The request already has the cached response: `If-None-Match` names its exact `ETag`.
    static boolean notModified(Stored stored, HttpServerRequest request) {
        String etag = stored.first("etag");
        if (etag == null || etag.isEmpty()) return false;
        String ifNoneMatch = request.getHeader("if-none-match");
        if (ifNoneMatch == null) return false;
        for (String candidate : ifNoneMatch.split(",")) if (candidate.strip().equals(etag)) return true;
        return false;
    }

    private static @Nullable String first(List<Header> headers, String name) {
        for (Header h : headers) if (h.name().equalsIgnoreCase(name)) return h.value();
        return null;
    }

    /// A request's cache key. The path and query are raw, as the app sees them: Thruster's decoded
    /// path and re-encoded query would make `/a%2Fb` and `/a/b` one entry.
    static final class Variant {
        private final String base;
        private final MultiMap requestHeaders;
        private List<String> names = List.of();

        Variant(HttpServerRequest request) {
            String host = request.getHeader("host");
            String query = request.query();
            this.base = request.method().name() + "\n" + request.path() + "\n" + (query == null ? "" : query) + "\n"
                + (host == null ? "" : host);
            this.requestHeaders = request.headers();
        }

        /// Varies on the names `vary` lists, lower-cased and sorted.
        void varyOn(@Nullable String vary) {
            if (vary == null || vary.isEmpty()) {
                names = List.of();
                return;
            }
            var list = new ArrayList<String>();
            for (String name : vary.split(",")) list.add(name.strip().toLowerCase(Locale.ROOT));
            list.sort(null);
            names = list;
        }

        String key() {
            var key = new StringBuilder(base);
            for (String name : names) key.append('\n').append(name).append('=').append(value(name));
            return key.toString();
        }

        boolean matches(List<Header> variant) {
            for (String name : names) {
                String stored = "";
                for (Header h : variant) {
                    if (h.name().equals(name)) {
                        stored = h.value();
                        break;
                    }
                }
                if (!stored.equals(value(name))) return false;
            }
            return true;
        }

        List<Header> headers() {
            return names.stream().map(name -> new Header(name, value(name))).toList();
        }

        private String value(String name) {
            String value = requestHeaders.get(name);
            return value == null ? "" : value;
        }
    }
}
