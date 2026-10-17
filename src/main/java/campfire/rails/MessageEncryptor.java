package campfire.rails;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/// `ActiveSupport::MessageEncryptor` with `aes-256-gcm`, as the encrypted cookie jar builds it:
/// `<base64 ciphertext>--<base64 12-byte IV>--<base64 16-byte auth tag>`, strict Base64.
public final class MessageEncryptor {
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 16;
    private static final int ENCODED_IV_LENGTH = 16;
    private static final int ENCODED_TAG_LENGTH = 24;

    private final SecretKeySpec key;
    private final Messages.Serializer serializer;

    public MessageEncryptor(byte[] secret, Messages.Serializer serializer) {
        if (secret.length != 32) throw new IllegalArgumentException("aes-256-gcm needs a 32-byte key");
        this.key = new SecretKeySpec(secret, "AES");
        this.serializer = serializer;
    }

    public String encryptAndSign(Object value, String purpose, Instant expiresAt) {
        byte[] plaintext = Messages.serializeWithMetadata(serializer, value, purpose, expiresAt);
        byte[] iv = SecureRandoms.bytes(IV_LENGTH);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, iv));
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] ciphertext = Arrays.copyOfRange(sealed, 0, sealed.length - TAG_LENGTH);
            byte[] tag = Arrays.copyOfRange(sealed, sealed.length - TAG_LENGTH, sealed.length);
            return Base64s.strictEncode(ciphertext) + "--" + Base64s.strictEncode(iv) + "--" + Base64s.strictEncode(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public Object decryptAndVerify(String message, String purpose, Instant now) {
        byte[] plaintext = decrypt(message);
        if (plaintext == null) throw new MessageException(MessageException.Kind.INVALID_SIGNATURE);
        return Messages.deserializeWithMetadata(serializer, plaintext, purpose, now, Base64s::strictDecode);
    }

    /// The decrypted bytes before any envelope handling, or null.
    public byte[] decrypt(String message) {
        int tagStart = message.length() - ENCODED_TAG_LENGTH;
        int ivStart = tagStart - 2 - ENCODED_IV_LENGTH;
        int ciphertextEnd = ivStart - 2;
        if (ciphertextEnd < 0 || !message.startsWith("--", tagStart - 2) || !message.startsWith("--", ciphertextEnd)) return null;
        byte[] ciphertext = Base64s.strictDecode(message.substring(0, ciphertextEnd));
        byte[] iv = Base64s.strictDecode(message.substring(ivStart, tagStart - 2));
        byte[] tag = Base64s.strictDecode(message.substring(tagStart));
        if (ciphertext == null || iv == null || tag == null || iv.length != IV_LENGTH || tag.length != TAG_LENGTH) return null;
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, iv));
            byte[] sealed = Arrays.copyOf(ciphertext, ciphertext.length + TAG_LENGTH);
            System.arraycopy(tag, 0, sealed, ciphertext.length, TAG_LENGTH);
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
