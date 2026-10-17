package campfire.db;

import static org.jooq.impl.DSL.param;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Param;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/// jOOQ as a type-safe SQL builder: queries are written against the tables and columns generated
/// from Rails' schema ({@code campfire.db.schema}), rendered once into constants, and run by
/// {@link Conn} on its cached prepared statements. Executing through jOOQ costs 6-10 µs a query
/// on top of SQLite's 3 µs, most of a cached page's time. Bind values are `?` in the order the
/// query names its `param(...)`s; everything else must be `inline(...)` to stay out of the
/// parameters, `NULL` included (`set(f, inline(null, f))`: `setNull(f)` renders `f = ?`).
public final class Sql {
    private Sql() {}

    private static final DSLContext SQLITE = DSL.using(SQLDialect.SQLITE);

    public static String render(Query query) {
        return SQLITE.render(query);
    }

    /// A query whose SQL depends on a count, the length of its `IN` list: rendered the first time
    /// each count is asked for. Counts are bounded by table sizes, so the memory stays small.
    public static IntFunction<String> perCount(IntFunction<Query> query) {
        var rendered = new ConcurrentHashMap<Integer, String>();
        return n -> rendered.computeIfAbsent(n, count -> render(query.apply(count)));
    }

    /// `n` bind parameters for `field`, for an `IN` list.
    public static <T> List<Param<T>> params(Field<T> field, int n) {
        return Collections.nCopies(n, param(field));
    }
}
