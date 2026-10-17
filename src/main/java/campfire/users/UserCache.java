package campfire.users;

import campfire.db.Conn;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Users looked up while building one page, each fetched at most once. Bound to the connection
/// the page reads on; deleted users are remembered as missing too.
@NullMarked
public final class UserCache {
    private final Conn conn;
    private final Map<Long, @Nullable User> users = new HashMap<>();

    public UserCache(Conn conn) {
        this.conn = conn;
    }

    public @Nullable User find(long id) {
        if (users.containsKey(id)) return users.get(id);
        User user = User.find(conn, id);
        users.put(id, user);
        return user;
    }

    /// Fetches the ones not seen yet in a single query.
    public void preload(List<Long> ids) {
        List<Long> missing = ids.stream().distinct().filter(id -> !users.containsKey(id)).toList();
        if (missing.isEmpty()) return;
        for (User user : User.findAll(conn, missing)) users.put(user.id(), user);
    }
}
