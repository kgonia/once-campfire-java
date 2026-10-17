package campfire.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The shared cache of public responses: static files, avatars, logos, QR codes and Active
/// Storage files. GET responses whose `Cache-Control` says `public` with a positive `max-age` are
/// kept until they expire and served again to any GET of the same URI. None of them vary on
/// anything but `Accept-Encoding`, so each entry keeps its body plain and, for text, gzipped;
/// a response that varies on another header isn't kept.
@NullMarked
final class FrontCache {
    /// Longer URIs aren't cached at all; the app's cacheable URLs are far shorter.
    static final int MAX_CACHEABLE_URI = 2048;
    static final long CAPACITY = 64L << 20;
    /// Larger responses aren't kept, and files up to this size are read whole to be kept.
    static final long MAX_ITEM_SIZE = 1L << 20;
    /// An entry's bookkeeping, charged on top of its key, headers and bodies.
    private static final int ENTRY_OVERHEAD = 256;

    record Header(String name, String value) {}

    /// `headers` are the response's own, without `Content-Encoding`, `Content-Length` or cookies.
    record Stored(int status, List<Header> headers, byte[] body, byte @Nullable [] gzip, Duration lifetime) {
        long size(String uri) {
            long size = body.length + (gzip == null ? 0 : gzip.length) + uri.length() + ENTRY_OVERHEAD;
            for (Header h : headers) size += h.name().length() + h.value().length();
            return size;
        }

        @Nullable String first(String name) {
            for (Header h : headers) if (h.name().equalsIgnoreCase(name)) return h.value();
            return null;
        }
    }

    private final Cache<String, Stored> entries = Caffeine.newBuilder()
        .maximumWeight(CAPACITY)
        .weigher((String uri, Stored stored) -> (int) Math.min(Integer.MAX_VALUE, stored.size(uri)))
        .expireAfter(new Expiry<String, Stored>() {
            @Override
            public long expireAfterCreate(String uri, Stored stored, long now) {
                return stored.lifetime().toNanos();
            }

            @Override
            public long expireAfterUpdate(String uri, Stored stored, long now, long current) {
                return stored.lifetime().toNanos();
            }

            @Override
            public long expireAfterRead(String uri, Stored stored, long now, long current) {
                return current;
            }
        })
        .build();

    /// A GET for the whole body, and not an overlong URI.
    static boolean cacheable(HttpServerRequest request) {
        if (request.method() != HttpMethod.GET || request.uri().length() > MAX_CACHEABLE_URI) return false;
        String range = request.getHeader("range");
        return range == null || range.isEmpty();
    }

    @Nullable Stored lookup(String uri) {
        return entries.getIfPresent(uri);
    }

    /// How long a response may be kept, or null when it mustn't be.
    static @Nullable Duration lifetime(int status, @Nullable String cacheControl, @Nullable String vary) {
        // `contains` first: almost every page is private, and that settles it before any parsing.
        if (cacheControl == null || !cacheControl.contains("public")) return null;
        if (status < 200 || status > 399 || status == 206 || status == 304) return null;
        if (vary != null) {
            for (String name : vary.split(",")) if (!name.isBlank() && !name.strip().equalsIgnoreCase("Accept-Encoding")) return null;
        }
        boolean isPublic = false;
        long maxAge = 0;
        for (String directive : cacheControl.split(",")) {
            String d = directive.strip().toLowerCase(Locale.ROOT);
            if (d.equals("public")) isPublic = true;
            else if (d.equals("private") || d.equals("no-cache") || d.equals("no-store")) return null;
            else if (d.startsWith("max-age=")) maxAge = seconds(d.substring("max-age=".length()));
        }
        return isPublic && maxAge > 0 ? Duration.ofSeconds(maxAge) : null;
    }

    private static long seconds(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// Keeps a response, unless it's larger than an item may be.
    void store(String uri, Stored stored) {
        if (stored.size(uri) <= MAX_ITEM_SIZE) entries.put(uri, stored);
    }

    /// The request already has the cached response: `If-None-Match` names its exact `ETag`, or
    /// without one, `If-Modified-Since` is its `Last-Modified`.
    static boolean notModified(Stored stored, HttpServerRequest request) {
        String ifNoneMatch = request.getHeader("if-none-match");
        if (ifNoneMatch != null) {
            String etag = stored.first("etag");
            if (etag == null || etag.isEmpty()) return false;
            for (String candidate : ifNoneMatch.split(",")) if (candidate.strip().equals(etag)) return true;
            return false;
        }
        String ifModifiedSince = request.getHeader("if-modified-since");
        return ifModifiedSince != null && ifModifiedSince.equals(stored.first("last-modified"));
    }
}
