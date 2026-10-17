package campfire.integrations.webpush;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import javax.crypto.KeyAgreement;

/// NIST P-256 (`secp256r1`) keys in the SEC1 encodings Web Push uses, with the JDK's EC provider
/// doing all the curve arithmetic (its ECDH rejects points that aren't on the curve).
final class P256 {
    private P256() {}

    static final ECParameterSpec PARAMS = params();

    private static ECParameterSpec params() {
        try {
            var parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /// The public key an uncompressed SEC1 point (`04‖X‖Y`) stands for, or null when it isn't
    /// one on the curve. (Browsers send `p256dh` uncompressed; the JDK has no compressed form.)
    static ECPublicKey decodePoint(byte[] encoded) {
        if (encoded == null || encoded.length != 65 || encoded[0] != 4) return null;
        var point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33)), new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65)));
        try {
            var key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, PARAMS));
            // KeyFactory doesn't check the point; an agreement with a throwaway key does.
            ecdh((ECPrivateKey) generate().getPrivate(), key);
            return key;
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /// A private key for a big-endian scalar of at most 32 bytes, or null unless 0 < d < n.
    static ECPrivateKey privateKey(byte[] scalar) {
        if (scalar == null || scalar.length > 32) return null;
        var d = new BigInteger(1, scalar);
        if (d.signum() <= 0 || d.compareTo(PARAMS.getOrder()) >= 0) return null;
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(d, PARAMS));
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    /// `04‖X‖Y`: the subjectPublicKey bit string, which ends the X.509 encoding.
    static byte[] encodeUncompressed(ECPublicKey key) {
        byte[] spki = key.getEncoded();
        return Arrays.copyOfRange(spki, spki.length - 65, spki.length);
    }

    static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /// The ECDH shared secret (the X coordinate of `private × public`); throws when the public
    /// key isn't a valid point.
    static byte[] ecdh(ECPrivateKey privateKey, ECPublicKey publicKey) throws GeneralSecurityException {
        var agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }
}
