package campfire.useragent;

import java.util.ArrayList;
import java.util.List;

/// `UserAgent::Version`. Equality is string equality; ordering is the gem's `<=>`, which is not a
/// total order (a non-numeric version sorts below everything it isn't equal to, from either side),
/// so this is not `Comparable`.
final class Version {
    static final Version NIL = new Version("");

    /// One element of `Version#to_a`.
    sealed interface Segment {}

    /// A run of digits, leading zeros stripped ("0" for zero), compared as an unbounded integer.
    record Int(String digits) implements Segment {
        static Int of(String digits) {
            int i = 0;
            while (i < digits.length() && digits.charAt(i) == '0') i++;
            return new Int(i == digits.length() ? "0" : digits.substring(i));
        }

        /// The value, or -1 past what a `long` holds: callers compare it with small numbers.
        long value() {
            return digits.length() > 18 ? -1 : Long.parseLong(digits);
        }
    }

    record Str(String string) implements Segment {}

    private static final Int ZERO = new Int("0");

    private final String string;
    private final boolean blank;
    private final boolean comparable;

    Version(String string) {
        this.string = string;
        this.blank = Strings.allRubySpace(string);
        int digits = Strings.digitRun(string, 0, string.length());
        this.comparable = !blank && digits > 0 && (digits == string.length() || string.charAt(digits) == '.');
    }

    /// `Version#nil?`: the string is empty or whitespace.
    boolean isNil() {
        return blank;
    }

    /// `version.to_s.present?` (ActiveSupport's Unicode-whitespace `blank?`).
    boolean isPresent() {
        return Strings.present(string);
    }

    /// `Version#to_a`, split out when asked for: a parse makes a version for every product, and
    /// only comparisons read the segments.
    List<Segment> toA() {
        if (blank) return List.of();
        if (!comparable) return List.of(new Str(string));
        return scanSequences(string);
    }

    /// `Version#<=>`: only the first six segments count.
    int compareRuby(Version other) {
        if (!comparable) return string.equals(other.string) ? 0 : -1;
        List<Segment> ours = toA(), theirs = other.toA();
        for (int i = 0; i < 6; i++) {
            Segment a = i < ours.size() ? ours.get(i) : ZERO;
            Segment b = i < theirs.size() ? theirs.get(i) : ZERO;
            if (a.equals(b)) continue;
            return switch (a) {
                case Str x when b instanceof Str y -> Strings.compareCodePoints(x.string(), y.string());
                case Str _ -> -1;
                case Int x when b instanceof Int y -> {
                    int byLength = Integer.compare(x.digits().length(), y.digits().length());
                    yield byLength != 0 ? byLength : Integer.signum(x.digits().compareTo(y.digits()));
                }
                case Int _ -> 1;
            };
        }
        return 0;
    }

    boolean lessThan(Version other) {
        return compareRuby(other) < 0;
    }

    /// `str.scan(/\d+|[A-Za-z][0-9A-Za-z-]*$/)`.
    private static List<Segment> scanSequences(String s) {
        var sequences = new ArrayList<Segment>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Strings.isDigit(c)) {
                int end = i + Strings.digitRun(s, i, n);
                sequences.add(Int.of(s.substring(i, end)));
                i = end;
            } else if (Strings.isAlpha(c)) {
                int end = i + 1;
                while (end < n && (Strings.isAlpha(s.charAt(end)) || Strings.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
                if (end == n) {
                    sequences.add(new Str(s.substring(i)));
                    i = end;
                } else {
                    i++;
                }
            } else {
                i++;
            }
        }
        return sequences;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Version v && v.string.equals(string);
    }

    @Override
    public int hashCode() {
        return string.hashCode();
    }

    @Override
    public String toString() {
        return string;
    }
}
