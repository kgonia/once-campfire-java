package campfire.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// One SQLite connection with a statement cache. Used by one thread at a time.
public class Conn implements AutoCloseable {
    @FunctionalInterface
    public interface Row<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private static final int CACHE_CAPACITY = 256;

    final Connection jdbc;
    private final Map<String, PreparedStatement> statements = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PreparedStatement> eldest) {
            if (size() <= CACHE_CAPACITY) return false;
            try {
                eldest.getValue().close();
            } catch (SQLException ignored) {
            }
            return true;
        }
    };

    Conn(Connection jdbc) {
        this.jdbc = jdbc;
    }

    PreparedStatement prepare(String sql) throws SQLException {
        PreparedStatement statement = statements.get(sql);
        if (statement == null) {
            statement = jdbc.prepareStatement(sql);
            statements.put(sql, statement);
        }
        return statement;
    }

    private PreparedStatement bind(String sql, Object[] args) throws SQLException {
        PreparedStatement statement = prepare(sql);
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            int index = i + 1;
            switch (arg) {
                case null -> statement.setNull(index, Types.NULL);
                case String s -> statement.setString(index, s);
                case Long l -> statement.setLong(index, l);
                case Integer n -> statement.setInt(index, n);
                case Boolean b -> statement.setInt(index, b ? 1 : 0);
                case Instant t -> statement.setString(index, Times.toDb(t));
                case Double d -> statement.setDouble(index, d);
                case byte[] bytes -> statement.setBytes(index, bytes);
                case Enum<?> e -> statement.setString(index, e.toString());
                default -> statement.setString(index, arg.toString());
            }
        }
        return statement;
    }

    public <T> T one(String sql, Row<T> row, Object... args) {
        try {
            var statement = bind(sql, args);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? row.map(rs) : null;
            }
        } catch (SQLException e) {
            throw new DbException(sql, e);
        }
    }

    public <T> List<T> list(String sql, Row<T> row, Object... args) {
        try {
            var statement = bind(sql, args);
            try (ResultSet rs = statement.executeQuery()) {
                var out = new ArrayList<T>();
                while (rs.next()) out.add(row.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new DbException(sql, e);
        }
    }

    public boolean exists(String sql, Object... args) {
        return one(sql, rs -> Boolean.TRUE, args) != null;
    }

    public long count(String sql, Object... args) {
        Long n = one(sql, rs -> rs.getLong(1), args);
        return n == null ? 0 : n;
    }

    public Long longValue(String sql, Object... args) {
        return one(sql, rs -> {
            long v = rs.getLong(1);
            return rs.wasNull() ? null : v;
        }, args);
    }

    public List<Long> longs(String sql, Object... args) {
        return list(sql, rs -> rs.getLong(1), args);
    }

    /// Runs an INSERT/UPDATE/DELETE and returns the changed row count.
    public int update(String sql, Object... args) {
        try {
            return bind(sql, args).executeUpdate();
        } catch (SQLException e) {
            throw new DbException(sql, e);
        }
    }

    /// Runs an INSERT and returns the new row's id, with `RETURNING` as Rails' adapter does: one
    /// statement inside the writer's lock instead of a second `last_insert_rowid()` query.
    public long insert(String sql, Object... args) {
        return count(sql + " RETURNING \"id\"", args);
    }

    public void execute(String sql) {
        try (Statement s = jdbc.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new DbException(sql, e);
        }
    }

    @Override
    public void close() {
        for (var statement : statements.values()) {
            try {
                statement.close();
            } catch (SQLException ignored) {
            }
        }
        statements.clear();
        try {
            jdbc.close();
        } catch (SQLException ignored) {
        }
    }

    // ResultSet helpers for nullable columns.

    public static Instant getTime(ResultSet rs, int column) throws SQLException {
        return Times.parseDb(rs.getString(column));
    }
}
