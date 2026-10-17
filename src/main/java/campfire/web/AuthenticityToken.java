package campfire.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/// Rails' token-based forgery protection, which its own controllers (Active Storage's) keep while
/// the app's pages rely on Fetch Metadata instead. A write passes with an `Origin` that is absent
/// or this site, and an `authenticity_token` parameter or `X-CSRF-Token` header matching the
/// session's `_csrf_token`: plain (32 bytes), masked with a one-time pad (64 bytes), or masked
/// as the global or per-form HMAC of it. The app never puts a token in the session, so only
/// sessions Rails made can pass.
public final class AuthenticityToken {
    private AuthenticityToken() {}

    private static final int LENGTH = 32;
    private static final String GLOBAL_IDENTIFIER = "!real_csrf_token";

    public static boolean verified(CurrentRequest request) {
        if (request.isGet()) return true;
        String origin = request.header("origin");
        if (origin != null && !origin.equals(request.baseUrl())) return false;
        byte[] real = decode(request.sessionValue("_csrf_token") instanceof String s ? s : null);
        if (real == null || real.length == 0) return false;
        return valid(request, real, request.params().string("authenticity_token")) || valid(request, real, request.header("x-csrf-token"));
    }

    private static boolean valid(CurrentRequest request, byte[] real, @Nullable String encoded) {
        byte[] masked = decode(encoded);
        if (masked == null) return false;
        if (masked.length == LENGTH) return MessageDigest.isEqual(masked, real);
        if (masked.length != LENGTH * 2) return false;
        byte[] token = new byte[LENGTH];
        for (int i = 0; i < LENGTH; i++) token[i] = (byte) (masked[i] ^ masked[LENGTH + i]);
        String path = request.path().endsWith("/") ? request.path().substring(0, request.path().length() - 1) : request.path();
        return MessageDigest.isEqual(token, hmac(real, GLOBAL_IDENTIFIER)) || MessageDigest.isEqual(token, real)
            || MessageDigest.isEqual(token, hmac(real, path + "#" + request.method().toLowerCase(java.util.Locale.ROOT)));
    }

    /// URL-safe Base64, padding optional; the standard alphabet is accepted too.
    private static byte @Nullable [] decode(@Nullable String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(encoded.replace('-', '+').replace('_', '/'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] hmac(byte[] key, String identifier) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
