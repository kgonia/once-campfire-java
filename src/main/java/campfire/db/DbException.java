package campfire.db;

import java.sql.SQLException;

public final class DbException extends RuntimeException {
    public DbException(String sql, SQLException cause) {
        super(cause.getMessage() + " [" + sql + "]", cause);
    }

    public DbException(String message) {
        super(message);
    }

    public boolean isUniqueViolation() {
        return getMessage() != null && getMessage().contains("UNIQUE constraint failed");
    }
}
