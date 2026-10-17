package campfire.integrations.net;

import java.time.Duration;

/// A point in time an operation must finish by, across however many connections and reads it
/// makes. {@link #NONE} never expires.
public final class Deadline {
    private final long at;
    private final boolean bounded;

    public static final Deadline NONE = new Deadline(0, false);

    private Deadline(long at, boolean bounded) {
        this.at = at;
        this.bounded = bounded;
    }

    public static Deadline after(Duration duration) {
        return new Deadline(System.nanoTime() + duration.toNanos(), true);
    }

    public boolean bounded() {
        return bounded;
    }

    /// Nanoseconds left (`Long.MAX_VALUE` for {@link #NONE}), never negative.
    public long remainingNanos() {
        return bounded ? Math.max(0, at - System.nanoTime()) : Long.MAX_VALUE;
    }

    public boolean expired() {
        return bounded && at - System.nanoTime() <= 0;
    }

    /// The earlier of this deadline and `duration` from now.
    public Deadline min(Duration duration) {
        long other = System.nanoTime() + duration.toNanos();
        return !bounded || other - at < 0 ? new Deadline(other, true) : this;
    }

    /// A socket timeout for one operation: `cap`, or less when the deadline is nearer; at least
    /// 1 ms (0 would mean forever).
    public int timeoutMillis(Duration cap) {
        long millis = Math.min(cap.toMillis(), bounded ? Math.ceilDiv(remainingNanos(), 1_000_000L) : Long.MAX_VALUE);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis));
    }
}
