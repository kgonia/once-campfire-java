package campfire.rails;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// The JSON Rails writes and reads. {@link #generate} is the json gem's `JSON.generate`;
/// {@link #encode} is `ActiveSupport::JSON.encode`, which also escapes `<`, `>` and `&`.
/// Values are plain Java: `Map` (insertion ordered), `List`, `String`, `Long`/`Integer`/`BigInteger`,
/// `Double`, `Boolean` and `null`. {@link Raw} embeds already-encoded JSON.
public final class Json {
    private Json() {}

    /// Already-encoded JSON, written as is.
    public record Raw(String json) {}

    public static String generate(Object value) {
        var out = new StringBuilder(128);
        write(out, value);
        return out.toString();
    }

    public static String encode(Object value) {
        return escapeHtmlEntities(generate(value));
    }

    /// `<`, `>` and `&` only occur inside JSON strings, so escaping them anywhere is safe.
    public static String escapeHtmlEntities(String json) {
        if (json.indexOf('<') < 0 && json.indexOf('>') < 0 && json.indexOf('&') < 0) return json;
        var out = new StringBuilder(json.length() + 16);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            switch (c) {
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(out, s);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case BigInteger b -> out.append(b);
            case Double d -> out.append(Double.isFinite(d) ? formatFloat(d) : "null");
            case Float f -> out.append(Float.isFinite(f) ? formatFloat(f) : "null");
            case BigDecimal d -> out.append('"').append(d.toPlainString()).append('"');
            case Raw raw -> out.append(raw.json());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    writeString(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    write(out, entry.getValue());
                }
                out.append('}');
            }
            case Iterable<?> list -> {
                out.append('[');
                boolean first = true;
                for (var item : list) {
                    if (!first) out.append(',');
                    first = false;
                    write(out, item);
                }
                out.append(']');
            }
            case Number n -> out.append(n);
            default -> writeString(out, value.toString());
        }
    }

    /// The json gem escapes the quote, backslash and control characters (lowercase hex), and
    /// leaves `/`, DEL and non-ASCII alone.
    public static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xf, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /// A finite float as json 2.x writes it: shortest round-trip digits, `1e+15` from 10^15 up,
    /// `0.00001` style down to 10^-10 or so.
    public static String formatFloat(double f) {
        String sign = (f < 0 || (f == 0 && 1 / f < 0)) ? "-" : "";
        if (f == 0) return sign + "0.0";
        var decimal = new BigDecimal(Double.toString(Math.abs(f))).stripTrailingZeros();
        String digits = decimal.unscaledValue().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        int k = exponent + 1 - digits.length();
        String body;
        if (k >= 0 && exponent < 15) {
            body = digits + "0".repeat(k) + ".0";
        } else if (k < 0 && (k > -7 || Math.abs(exponent) < 10)) {
            int point = exponent + 1;
            body = point <= 0 ? "0." + "0".repeat(-point) + digits : digits.substring(0, point) + "." + digits.substring(point);
        } else {
            String fraction = digits.length() > 1 ? "." + digits.substring(1) : "";
            body = digits.charAt(0) + fraction + "e" + (exponent < 0 ? '-' : '+') + Math.abs(exponent);
        }
        return sign + body;
    }

    // -- Parsing -------------------------------------------------------------------------------

    public static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message, null, false, false);
        }
    }

    /// Parses a JSON document; throws {@link ParseException} on malformed input.
    public static Object parse(String text) {
        var parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) throw new ParseException("trailing data");
        return value;
    }

    /// {@link #parse}, or null for malformed input (indistinguishable from a JSON `null`).
    public static Object parseOrNull(String text) {
        try {
            return parse(text);
        } catch (ParseException | StackOverflowError e) {
            return null;
        }
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object value(int depth) {
            if (depth > 512) throw new ParseException("too deep");
            if (pos >= s.length()) throw new ParseException("unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw new ParseException("bad literal");
            pos += word.length();
            return value;
        }

        Map<String, Object> object(int depth) {
            pos++;
            var map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw new ParseException("expected key");
                String key = string();
                skipWhitespace();
                if (peek() != ':') throw new ParseException("expected colon");
                pos++;
                skipWhitespace();
                map.put(key, value(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == '}') return map;
                if (c != ',') throw new ParseException("expected comma");
            }
        }

        List<Object> array(int depth) {
            pos++;
            var list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == ']') return list;
                if (c != ',') throw new ParseException("expected comma");
            }
        }

        char peek() {
            if (pos >= s.length()) throw new ParseException("unexpected end");
            return s.charAt(pos);
        }

        String string() {
            pos++;
            StringBuilder out = null;
            int start = pos;
            while (true) {
                if (pos >= s.length()) throw new ParseException("unterminated string");
                char c = s.charAt(pos);
                if (c == '"') {
                    String result = out == null ? s.substring(start, pos) : out.append(s, start, pos).toString();
                    pos++;
                    return result;
                }
                if (c < 0x20) throw new ParseException("control character in string");
                if (c == '\\') {
                    if (out == null) out = new StringBuilder();
                    out.append(s, start, pos);
                    pos++;
                    char e = peek();
                    pos++;
                    switch (e) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw new ParseException("bad unicode escape");
                            try {
                                out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw new ParseException("bad unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw new ParseException("bad escape");
                    }
                    start = pos;
                } else {
                    pos++;
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            boolean fractional = false;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') pos++;
                else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    fractional = true;
                    pos++;
                } else break;
            }
            String text = s.substring(start, pos);
            if (text.isEmpty() || text.equals("-")) throw new ParseException("bad number");
            try {
                if (fractional) return Double.parseDouble(text);
                if (text.length() < 19) return Long.parseLong(text);
                var big = new BigInteger(text);
                return big.bitLength() < 64 ? (Object) big.longValue() : big;
            } catch (NumberFormatException e) {
                throw new ParseException("bad number");
            }
        }
    }
}
