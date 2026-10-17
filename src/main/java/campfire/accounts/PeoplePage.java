package campfire.accounts;

import campfire.web.Params;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Which page of the account's people a request shows: 500 to a page, numbered from 1 (a
/// missing, malformed or non-positive `page` is the first).
@NullMarked
record PeoplePage(long number, long total) {
    static final int PER_PAGE = 500;

    static PeoplePage of(@Nullable String param, long total) {
        Long parsed = Params.parseId(param);
        return new PeoplePage(parsed == null || parsed < 1 ? 1 : Math.min(parsed, 1_000_000_000L), total);
    }

    long offset() {
        return (number - 1) * PER_PAGE;
    }

    /// The last page is the one holding the last person; with nobody, the first.
    boolean isLast() {
        return number == Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
    }
}
