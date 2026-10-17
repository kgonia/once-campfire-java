package campfire.rails;

import java.security.SecureRandom;
import java.util.HexFormat;

/// Ruby's `SecureRandom` helpers, over one shared generator.
public final class SecureRandoms {
    private SecureRandoms() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    public static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    /// `SecureRandom.hex(n)`: `n` random bytes as `2n` lowercase hex digits.
    public static String hex(int n) {
        return HexFormat.of().formatHex(bytes(n));
    }

    /// `SecureRandom.alphanumeric(n)`
    public static String alphanumeric(int n) {
        return chars(ALPHANUMERIC, n);
    }

    /// `SecureRandom.base58(n)`, as `has_secure_token` generates.
    public static String base58(int n) {
        return chars(BASE58, n);
    }

    private static String chars(String alphabet, int n) {
        var out = new char[n];
        for (int i = 0; i < n; i++) out[i] = alphabet.charAt(RANDOM.nextInt(alphabet.length()));
        return new String(out);
    }
}
