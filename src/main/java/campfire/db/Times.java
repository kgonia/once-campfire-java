package campfire.db;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/// Active Record's SQLite datetime format: `2026-03-01 09:00:00.123456` in UTC, with the
/// fraction left out when it's zero. Precision is microseconds (`datetime(6)`).
public final class Times {
    private Times() {}

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter NUMBER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO8601 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static volatile Instant frozen;

    /// `Time.current` at microsecond precision.
    public static Instant now() {
        Instant f = frozen;
        return (f != null ? f : Instant.now()).truncatedTo(ChronoUnit.MICROS);
    }

    /// Freezes the clock (tests and parity runs); null unfreezes it.
    public static void freeze(Instant at) {
        frozen = at;
    }

    public static String toDb(Instant time) {
        return (time.getNano() / 1000 == 0 ? SECONDS : MICROS).format(time);
    }

    /// Parses what Active Record and SQLite write; null when malformed.
    public static Instant parseDb(String text) {
        if (text == null) return null;
        text = text.strip();
        if (text.endsWith(" UTC")) text = text.substring(0, text.length() - 4);
        else if (text.endsWith("Z")) text = text.substring(0, text.length() - 1);
        if (text.length() < 19) return null;
        char sep = text.charAt(10);
        if (text.charAt(4) != '-' || text.charAt(7) != '-' || (sep != ' ' && sep != 'T') || text.charAt(13) != ':' || text.charAt(16) != ':') {
            return null;
        }
        try {
            int year = Integer.parseInt(text, 0, 4, 10);
            int month = Integer.parseInt(text, 5, 7, 10);
            int day = Integer.parseInt(text, 8, 10, 10);
            int hour = Integer.parseInt(text, 11, 13, 10);
            int minute = Integer.parseInt(text, 14, 16, 10);
            int second = Integer.parseInt(text, 17, 19, 10);
            int micros = 0;
            if (text.length() > 19) {
                if (text.charAt(19) != '.' || text.length() == 20) return null;
                String digits = text.substring(20, Math.min(text.length(), 26));
                for (int i = 20; i < text.length(); i++) if (!Character.isDigit(text.charAt(i))) return null;
                micros = Integer.parseInt((digits + "000000").substring(0, 6));
            }
            return LocalDateTime.of(year, month, day, hour, minute, second, micros * 1000).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// `to_fs(:number)`: `20260301090000`, as fresh avatar/logo URLs carry.
    public static String number(Instant time) {
        return NUMBER.format(time);
    }

    /// `Time#iso8601` in UTC (seconds), as `local_datetime_tag` writes.
    public static String iso8601(Instant time) {
        return ISO8601.format(time);
    }
}
