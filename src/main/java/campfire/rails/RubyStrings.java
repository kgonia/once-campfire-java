package campfire.rails;

import org.jspecify.annotations.NullMarked;

/// Ruby's `String` conversions as parameter handling sees them.
@NullMarked
public final class RubyStrings {
    private RubyStrings() {}

    /// `String#to_i`: optional leading whitespace and sign, an optional `0d`, then digits (an
    /// underscore allowed between two); anything else ends the number, and none at all is 0.
    /// Saturates at the `long` range where Ruby would grow a Bignum.
    public static long toI(String s) {
        int pos = 0;
        while (pos < s.length() && " \t\n\u000b\f\r".indexOf(s.charAt(pos)) >= 0) pos++;
        boolean negative = false;
        if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) negative = s.charAt(pos++) == '-';
        if (s.startsWith("0d", pos) || s.startsWith("0D", pos)) pos += 2;
        long n = 0;
        boolean previousDigit = false;
        for (; pos < s.length(); pos++) {
            char c = s.charAt(pos);
            if (c >= '0' && c <= '9') {
                n = n > (Long.MAX_VALUE - 9) / 10 ? Long.MAX_VALUE : n * 10 + (c - '0');
                previousDigit = true;
            } else if (c == '_' && previousDigit) {
                previousDigit = false;
            } else {
                break;
            }
        }
        return negative ? (n == Long.MAX_VALUE ? Long.MIN_VALUE : -n) : n;
    }
}
