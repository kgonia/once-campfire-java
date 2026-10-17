package campfire.db;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalDataSourceConfigurationSupplier;
import java.nio.file.Path;
import java.sql.SQLException;
import org.sqlite.JDBC;

/// A {@link Database} on `file` with the pools `application.properties` configures for the app.
public final class TestDatabase {
    private TestDatabase() {}

    public static Database open(Path file) {
        String url = "jdbc:sqlite:" + file.toAbsolutePath() + "?busy_timeout=5000&foreign_keys=true&journal_mode=WAL&synchronous=NORMAL";
        return new Database(file, pool(url, 1, "PRAGMA wal_autocheckpoint=0"), pool(url, 2, "PRAGMA query_only = true"));
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
