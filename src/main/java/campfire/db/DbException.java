package campfire.db;

import java.sql.SQLException;

public final class DbException extends RuntimeException {
    private final int code;

    public DbException(String sql, SQLException cause) {
        super(cause.getMessage() + " [" + sql + "]", cause);
        this.code = cause.getErrorCode();
    }

    public DbException(String message) {
        super(message);
        this.code = 0;
    }

    /// SQLite's primary result code (19 for constraint violations).
    public int code() {
        return code & 0xff;
    }

    public boolean isConstraintViolation() {
        return code() == 19 || (getMessage() != null && getMessage().contains("SQLITE_CONSTRAINT"));
    }

    public boolean isUniqueViolation() {
        return getMessage() != null && getMessage().contains("UNIQUE constraint failed");
    }
}
