package campfire.storage;

import campfire.rails.Base64s;
import campfire.rails.MessageException;
import campfire.rails.MessageVerifier;
import campfire.storage.RubyMarshal.Sym;
import io.vertx.core.json.Json;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// `ActiveStorage::Variation`: an ordered transformations hash, its Marshal-based digest (the
/// `variation_digest` column of `active_storage_variant_records`) and its signed URL key.
///
/// Values are as {@link RubyMarshal} takes them; a {@link Sym} is a Ruby symbol.
public final class Variation {
    /// `Message::Attachment` `:thumb`: `resize_to_limit: [1200, 800]` (the format defaults per blob).
    public static final Variation THUMB = resizeToLimit(1200, 800, null);
    /// `User::Avatar` `:square`.
    public static final Variation AVATAR_SQUARE = resizeToLimit(512, 512, "webp");
    /// `Account` logo `:large` and `:small`.
    public static final Variation LOGO_LARGE = resizeToLimit(512, 512, "png");
    public static final Variation LOGO_SMALL = resizeToLimit(192, 192, "png");
    /// The video poster in `Messages::AttachmentPresentation#video_preview_tag`:
    /// `preview(format: :webp, resize_to_limit: [1200, 800])`.
    public static final Variation VIDEO_POSTER = of("format", new Sym("webp"), "resize_to_limit", List.of(1200L, 800L));
    /// `Message::Attachment#process_attachment` for videos: `preview(format: :webp)`.
    public static final Variation VIDEO_PREVIEW = of("format", new Sym("webp"));

    private final Map<String, Object> transformations;

    public Variation(Map<String, ?> transformations) {
        this.transformations = Collections.unmodifiableMap(new LinkedHashMap<>(transformations));
    }

    /// Alternating keys and values, in Ruby's insertion order.
    public static Variation of(Object... keysAndValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return new Variation(map);
    }

    /// `resize_to_limit: [width, height]` plus an optional `format:` symbol, as the app's named
    /// variants declare them.
    public static Variation resizeToLimit(long width, long height, String format) {
        var map = new LinkedHashMap<String, Object>();
        map.put("resize_to_limit", List.of(width, height));
        if (format != null) map.put("format", new Sym(format));
        return new Variation(map);
    }

    public boolean isEmpty() {
        return transformations.isEmpty();
    }

    /// `default_to(defaults)`: `defaults.merge(transformations)`, so default keys come first and
    /// keep their position when overridden.
    public Variation defaultTo(Map<String, ?> defaults) {
        var merged = new LinkedHashMap<String, Object>(defaults);
        merged.putAll(transformations);
        return new Variation(merged);
    }

    public byte[] marshal() {
        return RubyMarshal.dump(transformations);
    }

    /// `OpenSSL::Digest::SHA1.base64digest Marshal.dump(transformations)`.
    public String digest() {
        try {
            return Base64s.strictEncode(MessageDigest.getInstance("SHA-1").digest(marshal()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// `transformations.fetch(:format, :png)`, which must be a known file extension.
    public String format() {
        Object format = transformations.getOrDefault("format", new Sym("png"));
        String name = switch (format) {
            case Sym s -> s.name();
            case String s -> s;
            default -> throw new StorageException("invalid variant format (" + format + ")");
        };
        if (ContentTypeDetector.byExtension(name) == null) throw new StorageException("invalid variant format (" + name + ")");
        return name;
    }

    public String contentType() {
        return java.util.Objects.requireNonNull(ContentTypeDetector.byExtension(format()));
    }

    /// The transformations as JSON, the way the verifier serializes them (symbols become strings).
    public String toJson() {
        return Json.encode(jsonValue(transformations));
    }

    private static Object jsonValue(Object value) {
        return switch (value) {
            case Sym s -> s.name();
            case List<?> l -> {
                var out = new ArrayList<>();
                for (Object o : l) out.add(jsonValue(o));
                yield out;
            }
            case Map<?, ?> m -> {
                var out = new LinkedHashMap<String, Object>();
                for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), jsonValue(e.getValue()));
                yield out;
            }
            case null, default -> value;
        };
    }

    /// `Variation#key`: `ActiveStorage.verifier.generate(transformations, purpose: :variation)`.
    public String key(MessageVerifier verifier) {
        return verifier.generateRaw(toJson(), "variation", null);
    }

    /// `Variation.decode(key)`: symbolized keys, but values stay strings. Null for a bad
    /// signature or a payload that isn't a transformations hash.
    public static Variation decode(MessageVerifier verifier, String key, Instant now) {
        Object data;
        try {
            data = verifier.verify(key, "variation", now);
        } catch (MessageException e) {
            return null;
        }
        if (!(data instanceof Map<?, ?> map)) return null;
        var transformations = new LinkedHashMap<String, Object>();
        for (var e : map.entrySet()) {
            Object value = fromJson(e.getValue());
            if (value == INVALID) return null;
            transformations.put(String.valueOf(e.getKey()), value);
        }
        return new Variation(transformations);
    }

    private static final Object INVALID = new Object();

    private static Object fromJson(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Long l -> l;
            case Integer i -> (long) i;
            case List<?> l -> {
                var out = new ArrayList<>();
                for (Object o : l) {
                    Object v = fromJson(o);
                    if (v == INVALID) yield INVALID;
                    out.add(v);
                }
                yield out;
            }
            case Map<?, ?> m -> {
                var out = new LinkedHashMap<String, Object>();
                for (var e : m.entrySet()) {
                    Object v = fromJson(e.getValue());
                    if (v == INVALID) yield INVALID;
                    out.put(String.valueOf(e.getKey()), v);
                }
                yield out;
            }
            // Floats and bignums aren't arguments any Campfire transformation takes.
            default -> INVALID;
        };
    }

    /// `resize_to_limit` operations in order as `[width, height]` (null for an unbounded side).
    /// Only `format` and `resize_to_limit` are supported: they're all Campfire defines.
    List<Integer[]> resizeOperations() {
        var operations = new ArrayList<Integer[]>();
        for (var entry : transformations.entrySet()) {
            String name = entry.getKey();
            Object argument = entry.getValue();
            if (name.equals("format") || blank(argument)) continue;
            if (name.equals("resize_to_limit") && argument instanceof List<?> args && args.size() == 2) {
                Integer width = dimension(args.get(0));
                Integer height = dimension(args.get(1));
                if (width == null && height == null) throw new StorageException("either width or height must be specified");
                operations.add(new Integer[] {width, height});
            } else {
                throw new StorageException("unsupported transformation " + name + ": " + argument);
            }
        }
        return operations;
    }

    private static Integer dimension(Object value) {
        return switch (value) {
            case null -> null;
            case Long l when l > 0 && l <= Integer.MAX_VALUE -> l.intValue();
            case Integer i when i > 0 -> i;
            default -> throw new StorageException("invalid resize_to_limit argument " + value);
        };
    }

    /// `Object#blank?` for the values a transformation can hold.
    private static boolean blank(Object value) {
        return switch (value) {
            case null -> true;
            case Boolean b -> !b;
            case String s -> s.isBlank();
            case List<?> l -> l.isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            default -> false;
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Variation v && v.transformations.equals(transformations);
    }

    @Override
    public int hashCode() {
        return transformations.hashCode();
    }

    @Override
    public String toString() {
        return transformations.toString();
    }
}
