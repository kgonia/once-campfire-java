package campfire.useragent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// The bits of Ruby string semantics the gem relies on. Ruby's `\s` and `\d` are ASCII-only.
final class Strings {
    private Strings() {}

    /// `\s` in a Ruby regexp.
    static boolean isRubySpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    /// What `String#strip` removes: `\s` and NUL.
    static boolean isStripped(char c) {
        return c == '\0' || isRubySpace(c);
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isVersionChar(char c) {
        return isDigit(c) || c == '.';
    }

    static boolean allRubySpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isRubySpace(s.charAt(i))) return false;
        }
        return true;
    }

    /// The length of the run of digits in `s[from, to)` starting at `from`.
    static int digitRun(String s, int from, int to) {
        int i = from;
        while (i < to && isDigit(s.charAt(i))) i++;
        return i - from;
    }

    static int versionRun(String s, int from) {
        int i = from;
        while (i < s.length() && isVersionChar(s.charAt(i))) i++;
        return i - from;
    }

    /// ActiveSupport's `String#present?`: not empty or Unicode whitespace (`[[:space:]]`).
    static boolean present(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isUnicodeSpace(s.charAt(i))) return true;
        }
        return false;
    }

    /// The Unicode White_Space property; every such character is in the BMP.
    private static boolean isUnicodeSpace(char c) {
        return (c >= '\t' && c <= '\r') || c == ' ' || c == '\u0085' || c == ' ' || c == ' '
                || (c >= ' ' && c <= ' ') || c == ' ' || c == ' ' || c == ' '
                || c == ' ' || c == '　';
    }

    static String downcase(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /// `haystack.downcase.include?(needle)` for a lowercase needle.
    static boolean containsIgnoringCase(String haystack, String needle) {
        return downcase(haystack).contains(needle);
    }

    /// `String#split(separator)` for a literal separator: trailing empty fields are dropped.
    static List<String> split(String s, String separator) {
        var parts = new ArrayList<String>();
        int from = 0;
        for (int at; (at = s.indexOf(separator, from)) >= 0; from = at + separator.length()) {
            parts.add(s.substring(from, at));
        }
        parts.add(s.substring(from));
        while (!parts.isEmpty() && parts.getLast().isEmpty()) parts.removeLast();
        return parts;
    }

    /// Byte order of the UTF-8 encodings, which is code point order.
    static int compareCodePoints(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i), y = b.codePointAt(j);
            if (x != y) return Integer.compare(x, y);
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    /// The capture of `/<prefix>([\d\.]+)/` at its leftmost match.
    static String versionAfter(String s, String prefix) {
        for (int at = s.indexOf(prefix); at >= 0; at = s.indexOf(prefix, at + 1)) {
            int start = at + prefix.length(), run = versionRun(s, start);
            if (run > 0) return s.substring(start, start + run);
        }
        return null;
    }
}
