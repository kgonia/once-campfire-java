package campfire.view;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Rendered fragments keyed by the record version they show (`<kind>/<id>-<updated_at>`): the
/// first rendering of a version is what every later page and broadcast repeats byte for byte.
/// A fragment may only depend on what its key names. Bounded like Rails' memory store: 32 MB,
/// counting 240 bytes of overhead per entry, least recently used first out.
@NullMarked
public final class FragmentCache {
    private FragmentCache() {}

    /// The same instance every time, so what it memoizes (its digest) is computed once.
    private static final Cache<String, Safe> STORE = Caffeine.newBuilder()
        .maximumWeight(32L * 1024 * 1024)
        .weigher((String key, Safe value) -> key.length() + value.utf8().length + 240)
        .build();

    public static Safe fetch(String key, Supplier<byte[]> render) {
        return STORE.get(key, k -> Safe.bytes(render.get()));
    }

    /// The cached fragment, or null; lets callers skip building a view model on a hit.
    public static @Nullable Safe read(String key) {
        return STORE.getIfPresent(key);
    }

    public static void clear() {
        STORE.invalidateAll();
    }
}
