package campfire.db;

import org.jooq.DSLContext;
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
}
