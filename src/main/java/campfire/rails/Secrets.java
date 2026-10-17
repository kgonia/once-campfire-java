package campfire.rails;

import java.time.Instant;
import java.time.ZoneOffset;

/// Everything Campfire signs or encrypts with `secret_key_base`, byte-compatible with Rails so
/// existing installs keep their sessions, avatar links and mentions.
public final class Secrets {
    public static final String SIGNED_COOKIE_SALT = "signed cookie";
    public static final String ENCRYPTED_COOKIE_SALT = "authenticated encrypted cookie";
    public static final String SIGNED_ID_SALT = "active_record/signed_id";
    public static final String SGID_SALT = "signed_global_ids";
    public static final String TURBO_SALT = "turbo/signed_stream_verifier_key";
    /// `GlobalID.app`.
    public static final String APP = "campfire";
    public static final String ATTACHABLE_PURPOSE = "attachable";

    private final KeyGenerator keyGenerator;
    private final MessageVerifier signedCookies;
    private final MessageEncryptor encryptedCookies;
    private final MessageVerifier signedIds;
    private final MessageVerifier sgids;
    private final MessageVerifier turboStreams;

    public Secrets(String secretKeyBase) {
        this.keyGenerator = new KeyGenerator(secretKeyBase);
        // `signed_cookie_digest` is unset, so the jar signs with SHA1 even though the key comes from PBKDF2-SHA256.
        this.signedCookies = new MessageVerifier(keyGenerator.generateKey(SIGNED_COOKIE_SALT, 64),
            MessageVerifier.Digest.SHA1, MessageVerifier.Encoding.STRICT, Messages.Serializer.NULL);
        this.encryptedCookies = new MessageEncryptor(keyGenerator.generateKey(ENCRYPTED_COOKIE_SALT, 32), Messages.Serializer.NULL);
        byte[] signedIdKey = keyGenerator.generateKey(SIGNED_ID_SALT, 64);
        this.signedIds = new MessageVerifier(signedIdKey, MessageVerifier.Digest.SHA256, MessageVerifier.Encoding.URL_SAFE, Messages.Serializer.JSON)
            .fallBackTo(new MessageVerifier(signedIdKey, MessageVerifier.Digest.SHA1, MessageVerifier.Encoding.STRICT, Messages.Serializer.JSON_ALLOW_MARSHAL));
        this.sgids = new MessageVerifier(keyGenerator.generateKey(SGID_SALT, 64),
            MessageVerifier.Digest.SHA1, MessageVerifier.Encoding.URL_SAFE_PADDED, Messages.Serializer.JSON_ALLOW_MARSHAL);
        this.turboStreams = new MessageVerifier(keyGenerator.generateKey(TURBO_SALT, 64),
            MessageVerifier.Digest.SHA256, MessageVerifier.Encoding.STRICT, Messages.Serializer.JSON);
    }

    public KeyGenerator keyGenerator() {
        return keyGenerator;
    }

    /// `Rails.application.message_verifier(name)`: SHA1, strict Base64, `:json_allow_marshal`.
    public MessageVerifier appVerifier(String name) {
        return new MessageVerifier(keyGenerator.generateKey(name, 64), MessageVerifier.Digest.SHA1, MessageVerifier.Encoding.STRICT,
            Messages.Serializer.JSON_ALLOW_MARSHAL);
    }

    // -- Cookies -------------------------------------------------------------------------------

    /// `cookies.permanent`: 20 calendar years from now.
    public static Instant permanentExpiresAt(Instant now) {
        return now.atOffset(ZoneOffset.UTC).plusYears(20).toInstant();
    }

    /// The raw value for `cookies.signed[name] = { value:, expires: }`.
    public String signCookie(String name, String value, Instant expiresAt) {
        return signedCookies.generate(Json.encode(value), "cookie." + name, expiresAt);
    }

    /// `cookies.signed[name]` as a string, or null.
    public String verifySignedCookie(String name, String raw, Instant now) {
        return loadCookie(readCookie(signedCookies::verify, name, raw, now)) instanceof String s ? s : null;
    }

    public String encryptCookie(String name, Object value, Instant expiresAt) {
        return encryptedCookies.encryptAndSign(Json.encode(value), "cookie." + name, expiresAt);
    }

    /// `cookies.encrypted[name]`, or null.
    public Object decryptCookie(String name, String raw, Instant now) {
        return loadCookie(readCookie(encryptedCookies::decryptAndVerify, name, raw, now));
    }

    private interface Reader {
        Object read(String message, String purpose, Instant now);
    }

    private static Object readCookie(Reader reader, String name, String raw, Instant now) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return reader.read(raw, "cookie." + name, now);
        } catch (MessageException e) {
            return null;
        }
    }

    /// `SerializerWithFallback[:json].load`; Marshal isn't allowed for cookies.
    private static Object loadCookie(Object dumped) {
        if (!(dumped instanceof String s)) return null;
        if (s.length() >= 2 && s.charAt(0) == 4 && s.charAt(1) == 8) return null;
        return Json.parseOrNull(s);
    }

    // -- Signed ids (`signed_id(purpose:, expires_in:)` / `find_signed`) -------------------------

    /// `modelName` is the record's base class, e.g. "User".
    public String signedId(String modelName, long id, String purpose, Instant expiresAt) {
        return signedIds.generate(id, combinePurposes(modelName, purpose), expiresAt);
    }

    /// The id to look up, or null.
    public Long verifySignedId(String modelName, String signedId, String purpose, Instant now) {
        if (signedId == null) return null;
        Object value = signedIds.verified(signedId, combinePurposes(modelName, purpose), now);
        return switch (value) {
            case Long l -> l;
            case Integer i -> (long) i;
            case String s -> {
                try {
                    yield Long.parseLong(s.strip());
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case null, default -> null;
        };
    }

    static String combinePurposes(String modelName, String purpose) {
        String model = underscore(modelName);
        if (purpose == null || purpose.isBlank()) return model;
        return model + "/" + purpose;
    }

    /// `String#underscore` for class names.
    static String underscore(String name) {
        String s = name.replace("::", "/");
        var out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char prev = i > 0 ? s.charAt(i - 1) : 0;
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                boolean afterLowerOrDigit = (prev >= 'a' && prev <= 'z') || (prev >= '0' && prev <= '9');
                boolean acronymEnd = (prev >= 'A' && prev <= 'Z') && (next >= 'a' && next <= 'z');
                if (afterLowerOrDigit || acronymEnd) out.append('_');
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c == '-' ? '_' : c);
            }
        }
        return out.toString();
    }

    // -- Global ids --------------------------------------------------------------------------

    /// `record.attachable_sgid`: GlobalID turns the leftover `expires_in: nil` into a query param.
    public String attachableSgid(GlobalId gid) {
        return sgids.generate(gid + "?expires_in", ATTACHABLE_PURPOSE, null);
    }

    public String sgid(GlobalId gid, String purpose, Instant expiresAt) {
        return sgids.generate(gid.toString(), purpose, expiresAt);
    }

    /// `SignedGlobalID.parse(sgid, for:)`: the GID if signature, purpose and expiry check out.
    public GlobalId locateSigned(String sgid, String purpose, Instant now) {
        if (sgid == null) return null;
        Object data;
        try {
            data = sgids.verify(sgid, purpose, now);
        } catch (MessageException e) {
            return null;
        }
        if (!(data instanceof String uri)) return null;
        GlobalId gid = GlobalId.parse(uri);
        return gid != null ? gid : GlobalId.fromParam(uri);
    }

    // -- Turbo stream names ------------------------------------------------------------------

    /// `Turbo::StreamsChannel.signed_stream_name`: parts joined with `:`, a record as its GID param.
    public String signedStreamName(String... streamables) {
        return turboStreams.generate(String.join(":", streamables), null, null);
    }

    /// The stream name, or null.
    public String verifiedStreamName(String signed) {
        if (signed == null) return null;
        return switch (turboStreams.verified(signed, null, Instant.EPOCH)) {
            case String s -> s;
            case Number n -> n.toString();
            case null, default -> null;
        };
    }
}
