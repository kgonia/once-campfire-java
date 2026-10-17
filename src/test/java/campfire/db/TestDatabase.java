package campfire.db;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import java.nio.file.Path;
import java.sql.SQLException;
import org.sqlite.JDBC;

/// A {@link Database} on a file with the pools `application.properties` configures for the app;
/// closing it closes the pools too, which Quarkus does for the app's.
public record TestDatabase(Database db, AgroalDataSource writer, AgroalDataSource readers) implements AutoCloseable {
    public static TestDatabase open(Path file) {
        String url = "jdbc:sqlite:" + file.toAbsolutePath() + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL&synchronous=NORMAL";
        var writer = pool(url, 1, "PRAGMA wal_autocheckpoint=0");
        var readers = pool(url, 2, "PRAGMA query_only = true");
        return new TestDatabase(new Database(file, writer, readers), writer, readers);
    }

    @Override
    public void close() {
        db.close();
        readers.close();
        writer.close();
    }

    private static AgroalDataSource pool(String url, int size, String initialSql) {
        try {
            return AgroalDataSource.from(new AgroalDataSourceConfigurationSupplier().connectionPoolConfiguration(pool -> pool.maxSize(size)
                .connectionFactoryConfiguration(factory -> factory.connectionProviderClass(JDBC.class).jdbcUrl(url).initialSql(initialSql))));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
