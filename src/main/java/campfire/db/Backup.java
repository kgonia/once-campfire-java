package campfire.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.sqlite.SQLiteConnection;

/// `script/admin/prepare-backup`: SQLite's online backup of the live database, all pages in one
/// step, into `backups/production.sqlite3`, which the reference's `post-restore` hook copies back.
public final class Backup {
    private Backup() {}

    public static void run(Path storage) throws Exception {
        Path backups = Files.createDirectories(storage.resolve("backups"));
        // Written beside the destination and moved over it, so a failed or concurrent backup never
        // leaves a torn file where ONCE expects the last good one.
        Path partial = Files.createTempFile(backups, ".backup-", ".sqlite3");
        try {
            copy(storage.resolve("db").resolve("production.sqlite3"), partial);
            Files.move(partial, backups.resolve("production.sqlite3"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    static void copy(Path database, Path target) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database + "?open_mode=1&busy_timeout=5000")) {
            int status = connection.unwrap(SQLiteConnection.class).getDatabase().backup("main", target.toString(), null);
            if (status != 0) throw new SQLException("backup failed with SQLite status " + status);
        }
    }
}
