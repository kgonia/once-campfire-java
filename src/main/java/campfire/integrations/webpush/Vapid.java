package campfire.integrations.webpush;

import campfire.rails.Base64s;
import campfire.rails.Json;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.LinkedHashMap;

/// VAPID identification (RFC 8292) as `WebPush::Request#build_vapid_header` writes it:
/// `vapid t=<ES256 JWT>,k=<public key>`, the JWT carrying `aud`, `exp` (12 hours out) and `sub`.
/// The keys are parsed once at boot so that a bad key turns Web Push off rather than failing
/// (and being blamed on) each subscription.
final class Vapid {
    /// `WebPush::Request#expiration`
    static final long EXPIRATION_SECONDS = 12 * 60 * 60;
    private static final String JWT_HEADER = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";

    final String subject;
    private final ECPrivateKey signingKey;
    private final byte[] publicKey;

    /// Why a key pair was refused.
    static final class VapidException extends Exception {
        VapidException(String message) {
            super(message, null, false, false);
        }
    }

    private Vapid(String subject, ECPrivateKey signingKey, byte[] publicKey) {
        this.subject = subject;
        this.signingKey = signingKey;
        this.publicKey = publicKey;
    }

    /// `VapidKey.from_keys(public_key, private_key)`: the private scalar signs; the public key is
    /// sent as given, so it must be the private key's.
    static Vapid of(String subject, String publicKey, String privateKey) throws VapidException {
        if (publicKey == null || privateKey == null || publicKey.isEmpty() && privateKey.isEmpty()) {
            throw new VapidException("VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY aren't set");
        }
        byte[] publicBytes = Base64s.urlsafeDecode(publicKey);
        ECPublicKey point = publicBytes == null ? null : P256.decodePoint(publicBytes);
        if (point == null) throw new VapidException("VAPID_PUBLIC_KEY isn't a Base64 P-256 public key");
        byte[] privateBytes = Base64s.urlsafeDecode(privateKey);
        ECPrivateKey signing = privateBytes == null ? null : P256.privateKey(privateBytes);
        if (signing == null) throw new VapidException("VAPID_PRIVATE_KEY isn't a Base64 P-256 private key");
        if (!matches(signing, point)) throw new VapidException("VAPID_PUBLIC_KEY isn't the public key of VAPID_PRIVATE_KEY");
        return new Vapid(subject, signing, publicBytes);
    }

    /// Whether `publicKey` verifies what `privateKey` signs.
    private static boolean matches(ECPrivateKey privateKey, ECPublicKey publicKey) {
        try {
            byte[] probe = "campfire vapid key check".getBytes(StandardCharsets.US_ASCII);
            var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(publicKey);
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /// The `Authorization` header for a push service at `audience` (`scheme://host`).
    String authorization(String audience, long nowSeconds) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("aud", audience);
        claims.put("exp", nowSeconds + EXPIRATION_SECONDS);
        claims.put("sub", subject);
        String signingInput = encode64(JWT_HEADER.getBytes(StandardCharsets.UTF_8)) + "."
            + encode64(Json.generate(claims).getBytes(StandardCharsets.UTF_8));
        byte[] signature;
        try {
            var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(signingKey);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            signature = signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return "vapid t=" + signingInput + "." + encode64(signature) + ",k=" + encode64(publicKey);
    }

    /// `trim_encode64`: urlsafe Base64 without padding.
    static String encode64(byte[] bytes) {
        return Base64s.urlsafeEncodeUnpadded(bytes);
    }
}
