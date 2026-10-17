package campfire.web;

import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The settings of the proxy layer in front of the app (what Thruster does for the Rails image),
/// read from `THRUSTER_<NAME>`, falling back to `<NAME>`. A value that doesn't parse as Go's
/// `strconv` would read it falls back to the default.
///
/// @param cacheSize        bytes the public response cache may hold
/// @param maxCacheItemSize the largest response it stores, in bytes
/// @param jitter           bytes of BREACH padding the proxy's own compression adds, 0 for none
/// @param maxRequestBody   bytes, 0 for no limit
@NullMarked
public record FrontConfig(long cacheSize, long maxCacheItemSize, boolean compression, boolean compressionDisabledOnAuth, int jitter,
                          long maxRequestBody) {
    private static final long MB = 1024 * 1024;

    public static FrontConfig fromEnv() {
        return from(System.getenv());
    }

    static FrontConfig from(Map<String, String> env) {
        Function<String, @Nullable String> find = key -> {
            String prefixed = env.get("THRUSTER_" + key);
            return prefixed != null ? prefixed : env.get(key);
        };
        return new FrontConfig(integer(find.apply("CACHE_SIZE"), 64 * MB), integer(find.apply("MAX_CACHE_ITEM_SIZE"), MB),
            bool(find.apply("GZIP_COMPRESSION_ENABLED"), true), bool(find.apply("GZIP_COMPRESSION_DISABLE_ON_AUTH"), false),
            (int) integer(find.apply("GZIP_COMPRESSION_JITTER"), 32), integer(find.apply("MAX_REQUEST_BODY"), 0));
    }

    /// `strconv.Atoi`: an optional sign and decimal digits, nothing else.
    private static long integer(@Nullable String value, long fallback) {
        if (value == null || !value.matches("[+-]?\\d+")) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /// `strconv.ParseBool`
    private static boolean bool(@Nullable String value, boolean fallback) {
        return switch (value == null ? "" : value) {
            case "1", "t", "T", "TRUE", "true", "True" -> true;
            case "0", "f", "F", "FALSE", "false", "False" -> false;
            default -> fallback;
        };
    }
}
