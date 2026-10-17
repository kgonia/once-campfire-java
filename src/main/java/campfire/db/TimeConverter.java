package campfire.db;

import java.time.Instant;
import org.jooq.impl.AbstractConverter;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// jOOQ's view of a `datetime(6)` column: the text Active Record stores (see {@link Times}), as an
/// `Instant`. Text that doesn't parse reads as null, as `Times.parseDb` has it.
@NullMarked
public final class TimeConverter extends AbstractConverter<String, Instant> {
    public TimeConverter() {
        super(String.class, Instant.class);
    }

    @Override
    public @Nullable Instant from(@Nullable String text) {
        return text == null ? null : Times.parseDb(text);
    }

    @Override
    public @Nullable String to(@Nullable Instant time) {
        return time == null ? null : Times.toDb(time);
    }
}
