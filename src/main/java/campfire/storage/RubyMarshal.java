package campfire.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// The subset of Ruby's `Marshal.dump` (format 4.8) needed to digest variation transformations:
/// `ActiveStorage::Variation#digest` is `SHA1.base64digest(Marshal.dump(transformations))`.
///
/// Values are `null`, `Boolean`, `Long`/`Integer`, {@link Sym}, `String` (UTF-8), `List` and
/// `Map<String, ?>` (a hash with symbol keys, in insertion order). Symbols and strings digest
/// differently: transformations written in Ruby carry symbols (`format: :webp`), while a variation
/// decoded from a signed key carries strings (`format: "webp"`).
public final class RubyMarshal {
    private RubyMarshal() {}

    /// A Ruby symbol.
    public record Sym(String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public static byte[] dump(Object value) {
        var writer = new Writer();
        writer.out.write(4);
        writer.out.write(8);
        writer.value(value);
        return writer.out.toByteArray();
    }

    private static final class Writer {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<String> symbols = new ArrayList<>();

        void value(Object value) {
            switch (value) {
                case null -> out.write('0');
                case Boolean b -> out.write(b ? 'T' : 'F');
                case Integer i -> integer(i);
                case Long l -> integer(l);
                case Sym s -> symbol(s.name());
                case String s -> {
                    // A string with an encoding is wrapped in an ivar list holding `E: true` (UTF-8).
                    out.write('I');
                    out.write('"');
                    bytes(s.getBytes(StandardCharsets.UTF_8));
                    writeLong(1);
                    symbol("E");
                    out.write('T');
                }
                case List<?> items -> {
                    out.write('[');
                    writeLong(items.size());
                    for (Object item : items) value(item);
                }
                case Map<?, ?> entries -> {
                    out.write('{');
                    writeLong(entries.size());
                    for (var entry : entries.entrySet()) {
                        symbol(String.valueOf(entry.getKey()));
                        value(entry.getValue());
                    }
                }
                default -> throw new IllegalArgumentException("can't marshal " + value.getClass().getSimpleName());
            }
        }

        /// Integers whose tagged VALUE fits in 32 bits are written inline (`i`), larger ones as
        /// bignums (`l`).
        void integer(long n) {
            if (n >= -(1L << 30) && n < (1L << 30)) {
                out.write('i');
                writeLong(n);
                return;
            }
            out.write('l');
            out.write(n < 0 ? '-' : '+');
            long magnitude = Math.abs(n);
            var digits = new ByteArrayOutputStream();
            if (n == Long.MIN_VALUE) {
                for (int i = 0; i < 7; i++) digits.write(0);
                digits.write(0x80);
            } else {
                while (magnitude > 0) {
                    digits.write((int) (magnitude & 0xff));
                    magnitude >>>= 8;
                }
            }
            if (digits.size() % 2 == 1) digits.write(0);
            writeLong(digits.size() / 2);
            out.writeBytes(digits.toByteArray());
        }

        void symbol(String name) {
            int index = symbols.indexOf(name);
            if (index >= 0) {
                out.write(';');
                writeLong(index);
            } else {
                symbols.add(name);
                out.write(':');
                bytes(name.getBytes(StandardCharsets.UTF_8));
            }
        }

        void bytes(byte[] bytes) {
            writeLong(bytes.length);
            out.writeBytes(bytes);
        }

        /// `w_long` in marshal.c.
        void writeLong(long n) {
            if (n == 0) {
                out.write(0);
            } else if (0 < n && n < 123) {
                out.write((int) (n + 5));
            } else if (-124 < n && n < 0) {
                out.write((int) ((n - 5) & 0xff));
            } else {
                byte[] buf = new byte[9];
                long x = n;
                for (int i = 1; i <= 8; i++) {
                    buf[i] = (byte) (x & 0xff);
                    x >>= 8;
                    if (x == 0) {
                        buf[0] = (byte) i;
                        out.write(buf, 0, i + 1);
                        return;
                    }
                    if (x == -1) {
                        buf[0] = (byte) -i;
                        out.write(buf, 0, i + 1);
                        return;
                    }
                }
            }
        }
    }
}
