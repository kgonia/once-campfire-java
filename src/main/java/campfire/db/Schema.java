package campfire.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// The schema exactly as `bin/rails db:prepare` creates it on a fresh database (`schema.sql` is
/// the `sqlite_master` of such a database), plus two additive indexes for room paging.
public final class Schema {
    private Schema() {}

    /// Every migration in `reference/db/migrate`, oldest first.
    static final List<String> MIGRATION_VERSIONS = List.of(
        "20231215043540", "20231220143106", "20240110071740", "20240115124901", "20240130003150",
        "20240130213001", "20240131105830", "20240209110503", "20250825100957", "20250825100958",
        "20250825100959", "20251126092013", "20251126115722", "20251126130131", "20251212154340");

    /// SHA1 of `reference/db/schema.rb`, which `db:schema:load` records in `ar_internal_metadata`.
    static final String SCHEMA_SHA1 = "f75da8dad38bfb179ffd757bd7a7c2b3f818bc29";

    /// Indexes added to the Rails schema on boot. Additive only, so the database still works with
    /// the Rails image: rooms page by `created_at`, refreshes look for `updated_at`.
    static final List<String> ADDITIONS = List.of(
        "CREATE INDEX IF NOT EXISTS \"index_messages_on_room_id_and_created_at\" ON \"messages\" (\"room_id\", \"created_at\")",
        "CREATE INDEX IF NOT EXISTS \"index_messages_on_room_id_and_updated_at\" ON \"messages\" (\"room_id\", \"updated_at\")");

    static void prepare(Conn conn) {
        if (conn.exists("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'schema_migrations'")) {
            var pending = MIGRATION_VERSIONS.stream().filter(v -> !conn.exists("SELECT 1 FROM schema_migrations WHERE version = ?", v)).toList();
            if (!pending.isEmpty()) {
                throw new DbException("pending migrations: " + String.join(", ", pending) + " (boot the Rails image once to migrate)");
            }
        } else {
            load(conn);
        }
        for (String addition : ADDITIONS) conn.execute(addition);
    }

    private static void load(Conn conn) {
        String sql;
        try (var in = Schema.class.getResourceAsStream("/campfire/db/schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        conn.execute("BEGIN IMMEDIATE TRANSACTION");
        try {
            for (String statement : splitStatements(sql)) conn.execute(statement);
            for (String version : MIGRATION_VERSIONS.reversed()) conn.update("INSERT INTO \"schema_migrations\" (\"version\") VALUES (?)", version);
            var now = Times.now();
            conn.update("INSERT INTO \"ar_internal_metadata\" (\"key\", \"value\", \"created_at\", \"updated_at\") VALUES (?, ?, ?, ?)",
                "environment", "production", now, now);
            conn.update("INSERT INTO \"ar_internal_metadata\" (\"key\", \"value\", \"created_at\", \"updated_at\") VALUES (?, ?, ?, ?)",
                "schema_sha1", SCHEMA_SHA1, now, now);
            conn.execute("COMMIT TRANSACTION");
        } catch (RuntimeException e) {
            conn.execute("ROLLBACK TRANSACTION");
            throw e;
        }
    }

    /// Splits on `;` at line ends; the schema has no semicolons inside statements.
    static List<String> splitStatements(String sql) {
        return java.util.Arrays.stream(sql.split(";\\s*\\n")).map(String::strip).filter(s -> !s.isEmpty())
            .map(s -> s.endsWith(";") ? s.substring(0, s.length() - 1) : s).toList();
    }
}
