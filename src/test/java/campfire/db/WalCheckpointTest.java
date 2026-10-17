package campfire.db;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalCheckpointTest {
    @TempDir
    Path dir;

    /// A WAL that outgrew the limit (here because a reader pinned it, as steady writes do) is
    /// checkpointed by the writer and its file emptied, so later commits don't checkpoint again.
    @Test
    void writerEmptiesAnOversizedWal() throws Exception {
        Path file = dir.resolve("production.sqlite3");
        Path wal = dir.resolve("production.sqlite3-wal");
        try (var test = TestDatabase.open(file)) {
            Database db = test.db();
            db.write(tx -> { tx.execute("CREATE TABLE blobs (data BLOB)"); return null; });
            try (var reader = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = reader.createStatement()) {
                reader.setAutoCommit(false);
                statement.executeQuery("SELECT count(*) FROM blobs").close();
                // Fewer commits than a size check takes, so none waits on the reader.
                for (int i = 0; i < 60; i++) db.write(tx -> tx.update("INSERT INTO blobs VALUES (randomblob(750000))"));
                reader.rollback();
            }
            assertTrue(Files.size(wal) > 40L * 1024 * 1024, "the reader should have pinned the WAL");
            for (int i = 0; i < 64; i++) db.write(tx -> tx.update("INSERT INTO blobs VALUES (1)"));
            assertTrue(Files.size(wal) < 1024 * 1024, "WAL still " + Files.size(wal) + " bytes");
        }
    }
}
