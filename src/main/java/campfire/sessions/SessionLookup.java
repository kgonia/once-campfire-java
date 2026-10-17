package campfire.sessions;

import campfire.db.Database;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jspecify.annotations.Nullable;

/// `Session.find_by(token:)` with its user, remembered until the next commit. Any commit (here or
/// in another process) changes SQLite's data version and empties the memory, so a revoked
/// session or a changed user is never served from it.
public final class SessionLookup {
    private record Entry(long version, Session.WithUser found) {}

    private final Database db;
    private final Cache<String, Entry> entries = Caffeine.newBuilder().maximumSize(10_000).build();

    public SessionLookup(Database db) {
        this.db = db;
    }

    /// The session and user for `token`, or null.
    public Session.WithUser find(String token) {
        return find(token, db.dataVersion());
    }

    /// The remembered answer, if it's still current; null means look it up.
    public Session.@Nullable WithUser cached(String token, long version) {
        Entry entry = entries.getIfPresent(token);
        return entry != null && entry.version() == version ? entry.found() : null;
    }

    /// The same, as of a data version the caller just read.
    public Session.WithUser find(String token, long version) {
        Session.WithUser cached = cached(token, version);
        if (cached != null) return cached;
        Session.WithUser found = db.read(conn -> Session.findByToken(conn, token));
        if (found != null) entries.put(token, new Entry(version, found));
        else entries.invalidate(token);
        return found;
    }

    public void forget(String token) {
        entries.invalidate(token);
    }
}
