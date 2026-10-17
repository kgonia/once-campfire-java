package campfire.rails;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/// `Rails.application.key_generator`: a caching PBKDF2-HMAC-SHA256 key generator with 1000
/// iterations (`load_defaults` 7.0+). Keys are 64 bytes by default; encrypted cookies ask for 32.
public final class KeyGenerator {
    public static final int ITERATIONS = 1000;

    // The JDK's PBKDF2 takes the password as chars and encodes them as UTF-8, which is what Ruby
    // hashes (also for an empty or non-ASCII secret).
    private final char[] secret;
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    public KeyGenerator(String secretKeyBase) {
        this.secret = secretKeyBase.toCharArray();
    }

    public byte[] generateKey(String salt) {
        return generateKey(salt, 64);
    }

    public byte[] generateKey(String salt, int length) {
        return cache.computeIfAbsent(salt + "\0" + length, k -> pbkdf2(salt.getBytes(StandardCharsets.UTF_8), length)).clone();
    }

    private byte[] pbkdf2(byte[] salt, int length) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(secret, salt, ITERATIONS, length * 8))
                .getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
