package campfire.integrations.webpush;

import campfire.rails.Base64s;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/// `WebPush::Encryption.encrypt` (web-push 3.1.0): RFC 8291 message encryption with the
/// RFC 8188 `aes128gcm` content coding, laid out as the gem does it: a single record whose size
/// field is the ciphertext length, and a plaintext padded with the 0x02 delimiter and one zero.
final class Encryption {
    private Encryption() {}

    private static final SecureRandom RANDOM = new SecureRandom();
    /// The gem's padding: the last-record delimiter and one zero byte.
    static final byte[] GEM_PADDING = {2, 0};
    private static final int MAX_RECORD = 4096;

    /// `ArgumentError` (blank or malformed input, an oversized payload) or, when `invalidKey`,
    /// `OpenSSL::PKey::EC::Point::Error` (the subscription's key isn't a P-256 point).
    static final class EncryptionException extends Exception {
        final boolean invalidKey;

        EncryptionException(String message, boolean invalidKey) {
            super(message, null, false, false);
            this.invalidKey = invalidKey;
        }
    }

    /// Encrypts `message` for the subscription's `p256dh` key and `auth` secret (both urlsafe
    /// Base64), with a fresh server key and salt.
    static byte[] encrypt(byte[] message, String p256dh, String auth) throws EncryptionException {
        var serverKeys = P256.generate();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encryptWith(message, p256dh, auth, (ECPrivateKey) serverKeys.getPrivate(),
            P256.encodeUncompressed((ECPublicKey) serverKeys.getPublic()), salt, null, GEM_PADDING);
    }

    /// The encryption with everything random given: the server key pair, the salt, the record
    /// size to write (null: the ciphertext length, as the gem writes it) and the padding.
    static byte[] encryptWith(byte[] message, String p256dh, String auth, ECPrivateKey serverPrivate, byte[] serverPublic, byte[] salt,
        Integer recordSize, byte[] padding) throws EncryptionException {
        if (message == null || message.length == 0) throw new EncryptionException("message cannot be blank", false);
        if (p256dh == null || p256dh.isEmpty()) throw new EncryptionException("p256dh cannot be blank", false);
        if (auth == null || auth.isEmpty()) throw new EncryptionException("auth cannot be blank", false);

        // OpenSSL::BN.new(bytes, 2) drops leading zero bytes before the point is decoded
        byte[] clientPublicBytes = stripLeadingZeros(decode64(p256dh));
        ECPublicKey clientPublic = P256.decodePoint(clientPublicBytes);
        if (clientPublic == null) throw new EncryptionException("invalid encoding", true);
        byte[] authSecret = decode64(auth);

        byte[] sharedSecret;
        try {
            sharedSecret = P256.ecdh(serverPrivate, clientPublic);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("invalid encoding", true);
        }
        byte[] info = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), clientPublicBytes, serverPublic);
        byte[] prk = hkdf(authSecret, sharedSecret, info, 32);
        byte[] contentEncryptionKey = hkdf(salt, prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        byte[] ciphertext;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentEncryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            ciphertext = cipher.doFinal(concat(message, padding));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        if (ciphertext.length > MAX_RECORD) throw new EncryptionException("encrypted payload is too big", false);

        var out = new ByteArrayOutputStream(16 + 4 + 1 + serverPublic.length + ciphertext.length);
        out.writeBytes(salt);
        out.writeBytes(ByteBuffer.allocate(4).putInt(recordSize != null ? recordSize : ciphertext.length).array());
        out.write(serverPublic.length);
        out.writeBytes(serverPublic);
        out.writeBytes(ciphertext);
        return out.toByteArray();
    }

    /// HKDF-SHA256 (RFC 5869): extract with `salt`, expand to `length` bytes of `info`.
    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) {
        try {
            var spec = HKDFParameterSpec.ofExtract().addIKM(ikm).addSalt(salt).thenExpand(info, length);
            return KDF.getInstance("HKDF-SHA256").deriveData(spec);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /// `WebPush.decode64` (`Base64.urlsafe_decode64` after padding): either alphabet.
    static byte[] decode64(String value) throws EncryptionException {
        byte[] decoded = Base64s.urlsafeDecode(value);
        if (decoded == null) throw new EncryptionException("invalid base64", false);
        return decoded;
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static byte[] stripLeadingZeros(byte[] bytes) {
        int zeros = 0;
        while (zeros < bytes.length && bytes[zeros] == 0) zeros++;
        return Arrays.copyOfRange(bytes, zeros, bytes.length);
    }
}
