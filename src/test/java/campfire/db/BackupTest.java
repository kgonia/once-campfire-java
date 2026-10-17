package campfire.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupTest {
    @TempDir
    Path storage;

    /// The backup sees committed rows still in the WAL of a connection that stays open, as the
    /// running server's does, and replaces the previous backup.
    @Test
    void copiesTheLiveDatabase() throws Exception {
        Files.createDirectories(storage.resolve("db"));
        Files.createDirectories(storage.resolve("backups"));
        Files.writeString(storage.resolve("backups/production.sqlite3"), "stale");
        try (var live = DriverManager.getConnection("jdbc:sqlite:" + storage.resolve("db/production.sqlite3") + "?journal_mode=WAL");
             var statement = live.createStatement()) {
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("CREATE TABLE rooms (name TEXT)");
            statement.execute("INSERT INTO rooms VALUES ('All Talk')");

            Backup.run(storage);
        }
        try (var copy = DriverManager.getConnection("jdbc:sqlite:" + storage.resolve("backups/production.sqlite3"));
             var rows = copy.createStatement().executeQuery("SELECT name FROM rooms")) {
            rows.next();
            assertEquals("All Talk", rows.getString(1));
        }
        try (var files = Files.list(storage.resolve("backups"))) {
            assertFalse(files.anyMatch(f -> f.getFileName().toString().startsWith(".backup-")));
        }
    }
}
