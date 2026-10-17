import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/// Creates the SQLite database jOOQ's code generator reads, by running Rails' schema the way
/// `campfire.db.Schema` loads it on a fresh install: statements split at `;` line ends.
/// Run by the build: `java -cp <sqlite-jdbc> SchemaDatabase.java SCHEMA.sql TARGET.sqlite3`.
public class SchemaDatabase {
    public static void main(String[] args) throws Exception {
        String schema = Files.readString(Path.of(args[0]));
        Path target = Path.of(args[1]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.deleteIfExists(target);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + target); var statement = connection.createStatement()) {
            for (String sql : schema.split(";\\s*\\n")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }
}
