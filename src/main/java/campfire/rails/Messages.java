package campfire.rails;

import io.vertx.core.json.DecodeException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/// `ActiveSupport::Messages::Metadata` and `SerializerWithFallback`: how a value, its purpose and
/// its expiry are packed into the bytes that get signed or encrypted.
///
/// Two envelopes exist. With a serializer Rails trusts for metadata (JSON) it is
/// `{"_rails":{"data":<value>,"exp":..,"pur":..}}`. With the cookie jars' `NullSerializer` it is
/// the legacy `{"_rails":{"message":"<base64 of the dumped value>","exp":..,"pur":..}}`.
public final class Messages {
    private Messages() {}

    public enum Serializer {
        /// The value is a string of already serialized bytes (what the cookie jars sign).
        NULL,
        /// `::JSON` (`JSON.dump`/`JSON.load`), as signed ids and Turbo stream names use.
        JSON,
        /// `SerializerWithFallback[:json]` with `ActiveSupport::JSON`.
        JSON_WITH_FALLBACK,
        /// `SerializerWithFallback[:json_allow_marshal]`.
        JSON_ALLOW_MARSHAL;

        byte[] dump(Object value) {
            return switch (this) {
                case NULL -> ((String) value).getBytes(StandardCharsets.UTF_8);
                // `::JSON` and `ActiveSupport::JSON` differ only in whether `<`, `>` and `&` are
                // escaped; Rails reads both back.
                case JSON, JSON_WITH_FALLBACK, JSON_ALLOW_MARSHAL -> Json.encode(value).getBytes(StandardCharsets.UTF_8);
            };
        }

        Object load(byte[] bytes) {
            switch (this) {
                case NULL:
                    return new String(bytes, StandardCharsets.UTF_8);
                case JSON:
                    if (bytes.length == 0) return null;
                    return parseJson(bytes);
                default:
                    if (bytes.length >= 2 && bytes[0] == 4 && bytes[1] == 8) {
                        if (this != JSON_ALLOW_MARSHAL) throw new MessageException(MessageException.Kind.INVALID_MESSAGE);
                        byte[] string = Marshal.loadString(bytes);
                        if (string == null) throw new MessageException(MessageException.Kind.INVALID_MESSAGE);
                        return new String(string, StandardCharsets.UTF_8);
                    }
                    return parseJson(bytes);
            }
        }

        boolean usesEnvelope() {
            return this != NULL;
        }

        /// Plain `Map`s and `List`s, as everything that reads a message expects.
        private static Object parseJson(byte[] bytes) {
            Object value;
            try {
                value = Json.decodeValue(new String(bytes, StandardCharsets.UTF_8));
            } catch (DecodeException e) {
                throw new MessageException(MessageException.Kind.INVALID_MESSAGE);
            }
            return switch (value) {
                case JsonObject object -> object.getMap();
                case JsonArray array -> array.getList();
                case null, default -> value;
            };
        }
    }

    private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /// `Time#iso8601(3)` in UTC, fraction truncated.
    public static String iso8601Millis(Instant time) {
        return ISO_MILLIS.format(time);
    }

    static byte[] serializeWithMetadata(Serializer serializer, Object value, String purpose, Instant expiresAt) {
        return serializeDumpedWithMetadata(serializer, serializer.dump(value), purpose, expiresAt);
    }

    static byte[] serializeDumpedWithMetadata(Serializer serializer, byte[] dumped, String purpose, Instant expiresAt) {
        if (purpose == null && expiresAt == null) return dumped;
        String expiry = expiresAt == null ? null : iso8601Millis(expiresAt);
        if (serializer.usesEnvelope()) {
            var out = new StringBuilder("{\"_rails\":{\"data\":").append(new String(dumped, StandardCharsets.UTF_8));
            if (expiry != null) out.append(",\"exp\":").append(Json.encode(expiry));
            if (purpose != null) out.append(",\"pur\":").append(Json.encode(purpose));
            return out.append("}}").toString().getBytes(StandardCharsets.UTF_8);
        }
        return ("{\"_rails\":{\"message\":" + Json.encode(Base64s.strictEncode(dumped))
            + ",\"exp\":" + Json.encode(expiry) + ",\"pur\":" + Json.encode(purpose) + "}}").getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] LEGACY_PREFIX = "{\"_rails\":{\"message\":".getBytes(StandardCharsets.UTF_8);

    static Object deserializeWithMetadata(Serializer serializer, byte[] bytes, String purpose, Instant now,
                                          Function<String, byte[]> decodeLegacyMessage) {
        if (startsWith(bytes, LEGACY_PREFIX)) {
            Object envelope;
            try {
                envelope = Json.decodeValue(new String(bytes, StandardCharsets.UTF_8), Map.class);
            } catch (DecodeException e) {
                throw new MessageException(MessageException.Kind.INVALID_SIGNATURE);
            }
            Map<?, ?> rails = extract(envelope, purpose, now);
            if (!(rails.get("message") instanceof String message)) throw new MessageException(MessageException.Kind.INVALID_SIGNATURE);
            byte[] dumped = decodeLegacyMessage.apply(message);
            if (dumped == null) throw new MessageException(MessageException.Kind.INVALID_SIGNATURE);
            return serializer.load(dumped);
        }
        Object value = serializer.load(bytes);
        if (value instanceof Map<?, ?> map && map.containsKey("_rails")) {
            return extract(value, purpose, now).get("data");
        } else if (purpose == null) {
            return value;
        } else {
            throw new MessageException(MessageException.Kind.PURPOSE_MISMATCH);
        }
    }

    private static Map<?, ?> extract(Object envelope, String purpose, Instant now) {
        if (!(((Map<?, ?>) envelope).get("_rails") instanceof Map<?, ?> rails)) {
            throw new MessageException(MessageException.Kind.INVALID_MESSAGE);
        }
        Object exp = rails.get("exp");
        if (exp instanceof String s) {
            Instant expiresAt;
            try {
                expiresAt = Instant.parse(s);
            } catch (RuntimeException e) {
                throw new MessageException(MessageException.Kind.INVALID_MESSAGE);
            }
            if (!now.isBefore(expiresAt)) throw new MessageException(MessageException.Kind.EXPIRED);
        } else if (exp != null) {
            throw new MessageException(MessageException.Kind.INVALID_MESSAGE);
        }
        if (!Objects.toString(rails.get("pur"), "").equals(purpose == null ? "" : purpose)) {
            throw new MessageException(MessageException.Kind.PURPOSE_MISMATCH);
        }
        return rails;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }
}
