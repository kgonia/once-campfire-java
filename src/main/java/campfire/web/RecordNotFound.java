package campfire.web;

import org.jspecify.annotations.Nullable;

/// A record the request names doesn't exist or isn't the user's to see; answered with the 404 page.
public final class RecordNotFound extends RuntimeException {
    public RecordNotFound() {
        super("not found", null, false, false);
    }

    /// `record`, or a 404 when it's null.
    public static <T> T require(@Nullable T record) {
        if (record == null) throw new RecordNotFound();
        return record;
    }
}
