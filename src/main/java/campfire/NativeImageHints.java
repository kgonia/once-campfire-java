package campfire;

import io.quarkus.runtime.annotations.RegisterForReflection;

/// What the native image must keep for reflection.
///
/// Caffeine picks a generated cache and node class by name for each combination of builder
/// options (strong or weak keys, size or weight bound, expiry); these are the ones the app's
/// caches (fragments, page digests, the response caches) were seen to load. A cache built with
/// new options needs its classes added here. Agroal instantiates the SQLite driver by name.
@RegisterForReflection(classNames = {
    "com.github.benmanes.caffeine.cache.FS", "com.github.benmanes.caffeine.cache.FSMS",
    "com.github.benmanes.caffeine.cache.PS", "com.github.benmanes.caffeine.cache.PSMW",
    "com.github.benmanes.caffeine.cache.PSW", "com.github.benmanes.caffeine.cache.PSWMW",
    "com.github.benmanes.caffeine.cache.SS", "com.github.benmanes.caffeine.cache.SSMW",
    "com.github.benmanes.caffeine.cache.SSMWA", "com.github.benmanes.caffeine.cache.SSMWW",
    "com.github.benmanes.caffeine.cache.WS", "com.github.benmanes.caffeine.cache.WSMS",
    "org.sqlite.JDBC",
})
final class NativeImageHints {
    private NativeImageHints() {}
}
