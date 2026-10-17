package campfire.sessions;

import campfire.db.Database;
import java.util.concurrent.ConcurrentHashMap;

/// `Session.find_by(token:)` with its user, remembered until the next commit. Any commit (here or
/// in another process) changes SQLite's data version and empties the memory, so a revoked
/// session or a changed user is never served from it.
public final class SessionLookup {
    private static final int MAX_ENTRIES = 10_000;

    private record Entry(long version, Session.WithUser found) {}

    private final Database db;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public SessionLookup(Database db) {
        this.db = db;
    }

    /// The session and user for `token`, or null.
    public Session.WithUser find(String token) {
        return find(token, db.dataVersion());
    }

    /// The same, as of a data version the caller just read.
    public Session.WithUser find(String token, long version) {
        Entry entry = entries.get(token);
        if (entry != null && entry.version() == version) return entry.found();
        Session.WithUser found = db.read(conn -> Session.findByToken(conn, token));
        if (found != null) {
            if (entries.size() >= MAX_ENTRIES) entries.clear();
            entries.put(token, new Entry(version, found));
        } else {
            entries.remove(token);
        }
        return found;
    }

    public void forget(String token) {
        entries.remove(token);
    }
}
