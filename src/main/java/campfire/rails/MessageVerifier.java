package campfire.rails;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/// `ActiveSupport::MessageVerifier`: `<base64 payload>--<hex HMAC of the base64 payload>`.
public final class MessageVerifier {
    public enum Digest {
        SHA1("HmacSHA1", 40),
        SHA256("HmacSHA256", 64);

        final String algorithm;
        final int hexLength;

        Digest(String algorithm, int hexLength) {
            this.algorithm = algorithm;
            this.hexLength = hexLength;
        }
    }

    /// How the payload is Base64-encoded when generating. Reading accepts either alphabet.
    public enum Encoding {
        /// `Base64.strict_encode64` (the default).
        STRICT,
        /// `url_safe: true`: URL-safe alphabet without padding.
        URL_SAFE,
        /// `GlobalID::Verifier`: URL-safe alphabet with padding.
        URL_SAFE_PADDED
    }

    private final SecretKeySpec key;
    private final Digest digest;
    private final Encoding encoding;
    private final Messages.Serializer serializer;
    private final List<MessageVerifier> rotations = new ArrayList<>();
    private final ThreadLocal<Mac> mac;

    public MessageVerifier(byte[] secret, Digest digest, Encoding encoding, Messages.Serializer serializer) {
        this.key = new SecretKeySpec(secret.length == 0 ? new byte[1] : secret, digest.algorithm);
        this.digest = digest;
        this.encoding = encoding;
        this.serializer = serializer;
        this.mac = ThreadLocal.withInitial(() -> {
            try {
                var m = Mac.getInstance(digest.algorithm);
                m.init(key);
                return m;
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public MessageVerifier fallBackTo(MessageVerifier rotation) {
        rotations.add(rotation);
        return this;
    }

    public String generate(Object value, String purpose, Instant expiresAt) {
        return sign(Messages.serializeWithMetadata(serializer, value, purpose, expiresAt));
    }

    /// `generate` for data the caller already encoded as JSON, so the caller controls key order.
    public String generateRaw(String dataJson, String purpose, Instant expiresAt) {
        return sign(Messages.serializeDumpedWithMetadata(serializer, dataJson.getBytes(StandardCharsets.UTF_8), purpose, expiresAt));
    }

    private String sign(byte[] serialized) {
        String encoded = switch (encoding) {
            case STRICT -> Base64s.strictEncode(serialized);
            case URL_SAFE -> Base64s.urlsafeEncodeUnpadded(serialized);
            case URL_SAFE_PADDED -> Base64s.urlsafeEncodePadded(serialized);
        };
        return encoded + "--" + hexDigest(encoded);
    }

    /// `verify`: the value, or a {@link MessageException} saying why it couldn't be read.
    public Object verify(String message, String purpose, Instant now) {
        try {
            return readMessage(message, purpose, now);
        } catch (MessageException error) {
            if (!error.kind().rotates()) throw error;
            for (var rotation : rotations) {
                try {
                    return rotation.readMessage(message, purpose, now);
                } catch (MessageException e) {
                    if (!e.kind().rotates()) throw e;
                }
            }
            throw error;
        }
    }

    /// `verified`: the value, or null.
    public Object verified(String message, String purpose, Instant now) {
        try {
            return verify(message, purpose, now);
        } catch (MessageException e) {
            return null;
        }
    }

    private Object readMessage(String message, String purpose, Instant now) {
        String encoded = extractEncoded(message);
        if (encoded == null) throw new MessageException(MessageException.Kind.INVALID_SIGNATURE);
        byte[] decoded = Base64s.urlsafeDecode(encoded);
        if (decoded == null) throw new MessageException(MessageException.Kind.INVALID_SIGNATURE);
        return Messages.deserializeWithMetadata(serializer, decoded, purpose, now, Base64s::urlsafeDecode);
    }

    private String extractEncoded(String signed) {
        if (signed == null) return null;
        int index = signed.length() - digest.hexLength - 2;
        if (index < 0 || !signed.startsWith("--", index)) return null;
        String encoded = signed.substring(0, index);
        String given = signed.substring(index + 2);
        if (encoded.isBlank() || given.isBlank()) return null;
        byte[] expected = hexDigest(encoded).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8)) ? encoded : null;
    }

    private String hexDigest(String data) {
        return HexFormat.of().formatHex(mac.get().doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
