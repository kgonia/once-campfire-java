package campfire.rails;

import java.util.Arrays;

/// The sliver of Ruby's Marshal format needed to read Rails 7-era signed messages, whose payload
/// is a marshaled String (`Marshal.dump("gid://campfire/User/1")`). Anything else is rejected.
final class Marshal {
    private Marshal() {}

    /// Loads `"\x04\x08" ["I"] '"' <len> <bytes> [<ivars>]`, or null.
    static byte[] loadString(byte[] dumped) {
        if (dumped.length < 3 || dumped[0] != 4 || dumped[1] != 8) return null;
        int pos = 2;
        if (dumped[pos] == 'I') pos++;
        if (pos >= dumped.length || dumped[pos] != '"') return null;
        pos++;
        if (pos >= dumped.length) return null;
        int first = dumped[pos++];
        long length;
        if (first == 0) {
            length = 0;
        } else if (first >= 1 && first <= 4) {
            if (pos + first > dumped.length) return null;
            length = 0;
            for (int i = first - 1; i >= 0; i--) length = (length << 8) | (dumped[pos + i] & 0xff);
            pos += first;
        } else if (first >= -4 && first <= -1) {
            return null;
        } else if (first >= 5) {
            length = first - 5;
        } else {
            return null;
        }
        if (length < 0 || pos + length > dumped.length) return null;
        return Arrays.copyOfRange(dumped, pos, pos + (int) length);
    }
}
