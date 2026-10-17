package campfire;

import campfire.db.Backup;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.nio.file.Path;

/// `campfire backup` is ONCE's pre-backup hook. It runs beside the live server without booting the
/// app, whose startup would bind the HTTP port and reset the server's memberships. The storage
/// root comes from the same variable `campfire.storage` reads.
@QuarkusMain
public class Main {
    public static void main(String... args) throws Exception {
        if (args.length == 1 && args[0].equals("backup")) {
            Backup.run(Path.of(System.getenv().getOrDefault("CAMPFIRE_STORAGE", "/rails/storage")));
            return;
        }
        Quarkus.run(args);
    }
}
