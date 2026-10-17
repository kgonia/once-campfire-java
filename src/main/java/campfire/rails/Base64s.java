package campfire.rails;

import java.util.Base64;

/// Ruby's Base64 flavors as Rails uses them.
public final class Base64s {
    private Base64s() {}

    /// `Base64.strict_encode64`.
    public static String strictEncode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /// `Base64.urlsafe_encode64(data, padding: false)`.
    public static String urlsafeEncodeUnpadded(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /// `Base64.urlsafe_encode64(data)`.
    public static String urlsafeEncodePadded(byte[] data) {
        return Base64.getUrlEncoder().encodeToString(data);
    }

    /// `Base64.strict_decode64`: standard alphabet, canonical padding, no whitespace. Null when invalid.
    public static byte[] strictDecode(String encoded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            // Re-encoding must give the input back: that rejects missing or extra padding and the
            // non-zero trailing bits Ruby refuses.
            return strictEncode(decoded).equals(encoded) ? decoded : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /// `Base64.urlsafe_decode64`: pads an unpadded string, translates `-_` to `+/`, then decodes
    /// strictly. Accepts either alphabet. Null when invalid.
    public static byte[] urlsafeDecode(String encoded) {
        String translated = encoded.replace('-', '+').replace('_', '/');
        if (!encoded.endsWith("=") && encoded.length() % 4 != 0) translated += "=".repeat(4 - encoded.length() % 4);
        return strictDecode(translated);
    }
}
