package campfire.db;

import io.agroal.api.AgroalDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.sqlite.JDBC;

/// The SQLite database: one write connection behind a lock, a pool of read-only connections, an
/// observer connection whose `data_version` tells caches when anything committed (including
/// another process), and a checkpointer that keeps WAL checkpoints off the write path.
///
/// The writer and the readers are the `writer` and `readers` datasources (Agroal pools, see
/// `application.properties`). Agroal doesn't cache prepared statements, and SQLite prepares per
/// connection, so each pooled connection keeps its {@link Conn}, statement cache and all, by the
/// SQLite connection underneath; the pools don't retire connections. The observer and checkpointer
/// are connections of their own, opened with the writer's URL: `data_version` only means something
/// asked of the same connection every time.
///
/// Writes run in `BEGIN IMMEDIATE` (`default_transaction_mode: immediate` in the reference's
/// `config/database.yml`); their after-commit work runs in order once the transaction commits,
/// the way Active Record runs `after_commit` callbacks. A write nested in another on the same
/// thread joins it, and a read inside a write sees the write's own changes.
public final class Database implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    /// Commits between background PASSIVE checkpoints (SQLite's auto-checkpoint is 1,000 pages).
    private static final int CHECKPOINT_EVERY_COMMITS = 64;
    /// WAL size at which the writer itself checkpoints with RESTART.
    private static final long WAL_LIMIT_BYTES = 40L * 1024 * 1024;

    @FunctionalInterface
    public interface Work<T> {
        T run(Tx tx);
    }

    @FunctionalInterface
    public interface Read<T> {
        T run(Conn conn);
    }

    /// A write in progress: the writer connection and its queued after-commit work.
    public static final class Tx extends Conn {
        private List<Runnable> afterCommit = new ArrayList<>();
        private boolean inTransaction;

        Tx(java.sql.Connection jdbc) {
            super(jdbc);
        }

        /// Runs `work` once the transaction commits, or right away outside one.
        public void afterCommit(Runnable work) {
            if (inTransaction) afterCommit.add(work);
            else work.run();
        }

        public boolean inTransaction() {
            return inTransaction;
        }
    }

    private final Path path;
    private final AgroalDataSource writerPool;
    private final AgroalDataSource readerPool;
    private final Map<Connection, Tx> writers = new ConcurrentHashMap<>();
    private final Map<Connection, Conn> readers = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private final Conn observer;
    private final ReentrantLock observerLock = new ReentrantLock();
    private final Conn checkpointer;
    private final Thread checkpointThread;
    private final java.util.concurrent.Semaphore checkpointWake = new java.util.concurrent.Semaphore(0);
    private final ReentrantLock checkpointRunning = new ReentrantLock();
    private int commitsSinceCheckpoint;
    /// The write in progress on this thread, which nested reads and writes join.
    private static final ScopedValue<Tx> CURRENT = ScopedValue.newInstance();

    /// `path` is the file both pools open.
    public Database(Path path, AgroalDataSource writerPool, AgroalDataSource readerPool) {
        this.path = path;
        this.writerPool = writerPool;
        this.readerPool = readerPool;
        String url = writerPool.getConfiguration().connectionPoolConfiguration().connectionFactoryConfiguration().jdbcUrl();
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            // Before either pool opens a connection: switching a new file to WAL needs it alone, and
            // pools open theirs concurrently, which would then find the file locked.
            this.checkpointer = new Conn(open(url, false));
            Schema.prepare(checkpointer);
            this.observer = new Conn(open(url, true));
            this.checkpointThread = Thread.ofPlatform().name("campfire-db-checkpointer").daemon().start(this::runCheckpoints);
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("can't open " + path + ": " + e.getMessage(), e);
        }
    }

    public Path path() {
        return path;
    }

    private static java.sql.Connection open(String url, boolean readOnly) throws SQLException {
        var connection = JDBC.createConnection(url, new java.util.Properties());
        try (var s = connection.createStatement()) {
            if (readOnly) s.execute("PRAGMA query_only = true");
        }
        return connection;
    }

    /// The {@link Tx} of a connection borrowed from the writer pool.
    private Tx writer(Connection borrowed) throws SQLException {
        return writers.computeIfAbsent(borrowed.unwrap(Connection.class), Tx::new);
    }

    /// Runs `work` in a write transaction (or joins the current one), then its after-commit work.
    public <T> T write(Work<T> work) {
        if (CURRENT.isBound()) return work.run(CURRENT.get());
        writeLock.lock();
        Connection borrowed = null;
        Tx writer = null;
        try {
            borrowed = writerPool.getConnection();
            Tx bound = writer = writer(borrowed);
            return ScopedValue.where(CURRENT, bound).call(() -> transact(bound, work));
        } catch (SQLException e) {
            throw new DbException("can't get the write connection", e);
        } finally {
            if (writer != null) writer.inTransaction = false;
            if (borrowed != null) release(borrowed);
            writeLock.unlock();
        }
    }

    private <T> T transact(Tx writer, Work<T> work) {
        T value;
        writer.inTransaction = true;
        writer.afterCommit = new ArrayList<>();
        writer.execute("BEGIN IMMEDIATE TRANSACTION");
        boolean committed = false;
        try {
            value = work.run(writer);
            writer.execute("COMMIT TRANSACTION");
            committed = true;
        } finally {
            if (!committed) {
                try {
                    writer.execute("ROLLBACK TRANSACTION");
                } catch (DbException ignored) {
                }
            }
            writer.inTransaction = false;
        }
        List<Runnable> queued = writer.afterCommit;
        writer.afterCommit = new ArrayList<>();
        noteCommit(writer);
        // After-commit work may write again (Active Record runs it outside the transaction).
        RuntimeException first = null;
        for (Runnable hook : queued) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "after_commit hook failed", e);
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
        return value;
    }

    private static void release(Connection borrowed) {
        try {
            borrowed.close();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "returning a connection to its pool failed", e);
        }
    }

    /// Runs `read` on a read connection, or on the writer when inside a write on this thread.
    public <T> T read(Read<T> read) {
        if (CURRENT.isBound()) return read.run(CURRENT.get());
        Connection borrowed;
        Conn conn;
        try {
            borrowed = readerPool.getConnection();
            conn = readers.computeIfAbsent(borrowed.unwrap(Connection.class), Conn::new);
        } catch (SQLException e) {
            throw new DbException("can't get a read connection", e);
        }
        try {
            return read.run(conn);
        } finally {
            release(borrowed);
        }
    }

    /// Changes whenever any connection, in this process or another, commits.
    public long dataVersion() {
        observerLock.lock();
        try {
            return observer.count("PRAGMA data_version");
        } finally {
            observerLock.unlock();
        }
    }

    private void noteCommit(Tx writer) {
        if (++commitsSinceCheckpoint < CHECKPOINT_EVERY_COMMITS) return;
        commitsSinceCheckpoint = 0;
        try {
            if (Files.size(Path.of(path + "-wal")) >= WAL_LIMIT_BYTES) {
                checkpointRunning.lock();
                try {
                    writer.execute("PRAGMA wal_checkpoint(RESTART)");
                } finally {
                    checkpointRunning.unlock();
                }
                return;
            }
        } catch (java.io.IOException | DbException ignored) {
        }
        if (checkpointWake.availablePermits() == 0) checkpointWake.release();
    }

    private void runCheckpoints() {
        while (true) {
            try {
                checkpointWake.acquire();
            } catch (InterruptedException e) {
                return;
            }
            checkpointWake.drainPermits();
            checkpointRunning.lock();
            try {
                checkpointer.execute("PRAGMA wal_checkpoint(PASSIVE)");
            } catch (DbException e) {
                LOG.warning("WAL checkpoint failed: " + e.getMessage());
            } finally {
                checkpointRunning.unlock();
            }
        }
    }

    @Override
    public void close() {
        checkpointThread.interrupt();
        writeLock.lock();
        try {
            readers.values().forEach(Conn::close);
            writers.values().forEach(Conn::close);
            readerPool.close();
            writerPool.close();
            observer.close();
            checkpointer.close();
        } finally {
            writeLock.unlock();
        }
    }
}
