package campfire.search;

import campfire.db.Database;
import campfire.messages.Message;
import campfire.messages.MessagePresenter;
import campfire.rooms.Room;
import campfire.view.View;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Full-text search over the messages a user can reach, and their ten most recent searches.
/// Each operation runs the search first, so a query SQLite's FTS rejects fails all of them alike.
@ApplicationScoped
@NullMarked
public class SearchService {
    /// Unicode word characters: letters, marks, digits, connector punctuation, join controls.
    private static final Pattern WORD = Pattern.compile("\\w", Pattern.UNICODE_CHARACTER_CLASS);

    @Inject
    Database db;

    /// The results, the recent searches and the way back, read together.
    public SearchPage page(View v, long userId, @Nullable String q, @Nullable Long lastRoomId) {
        String query = searchable(q);
        return db.read(conn -> {
            List<Message> messages = query == null ? List.of() : Message.searchReachable(conn, userId, query);
            List<String> recent = Search.recentFor(conn, userId).stream().map(Search::query).toList();
            Room back = Room.lastVisitedForUser(conn, userId, lastRoomId);
            return new SearchPage(query, q, new MessagePresenter(conn, v).render(messages), recent, back == null ? 0 : back.id());
        });
    }

    /// Remembers the search; returns the query as stored.
    public String record(long userId, @Nullable String q) {
        search(userId, q);
        String query = wordsOnly(q);
        // Rails stores the query as given and the column is NOT NULL.
        if (query == null) throw new IllegalStateException("NOT NULL constraint failed: searches.query");
        db.write(tx -> {
            Search.record(tx, userId, query);
            return null;
        });
        return query;
    }

    public void clear(long userId, @Nullable String q) {
        search(userId, q);
        db.write(tx -> {
            Search.clear(tx, userId);
            return null;
        });
    }

    /// The query with everything but word characters turned into spaces, so FTS5 sees no syntax.
    static @Nullable String wordsOnly(@Nullable String q) {
        if (q == null) return null;
        var out = new StringBuilder(q.length());
        q.codePoints().forEach(c -> out.appendCodePoint(WORD.matcher(Character.toString(c)).matches() ? c : ' '));
        return out.toString();
    }

    private static @Nullable String searchable(@Nullable String q) {
        String query = wordsOnly(q);
        return query == null || query.isBlank() ? null : query;
    }

    private List<Message> search(long userId, @Nullable String q) {
        String query = searchable(q);
        if (query == null) return List.of();
        return db.read(conn -> Message.searchReachable(conn, userId, query));
    }
}
