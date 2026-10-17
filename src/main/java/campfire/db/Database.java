package campfire.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.sqlite.JDBC;

/// The SQLite database: one write connection behind a lock, a pool of read-only connections, an
/// observer connection whose `data_version` tells caches when anything committed (including
/// another process), and a checkpointer that keeps WAL checkpoints off the write path.
///
/// Writes run in `BEGIN IMMEDIATE` (`default_transaction_mode: immediate` in the reference's
/// `config/database.yml`); their after-commit work runs in order once the transaction commits,
/// the way Active Record runs `after_commit` callbacks. A write nested in another on the same
/// thread joins it, and a read inside a write sees the write's own changes.
public final class Database implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Database.class.getName());

    /// `timeout: 5000` in `config/database.yml`.
    static final int BUSY_TIMEOUT_MS = 5000;
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
    private final Tx writer;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final BlockingQueue<Conn> readers;
    private final Conn observer;
    private final ReentrantLock observerLock = new ReentrantLock();
    private final Conn checkpointer;
    private final Thread checkpointThread;
    private final java.util.concurrent.Semaphore checkpointWake = new java.util.concurrent.Semaphore(0);
    private final ReentrantLock checkpointRunning = new ReentrantLock();
    private int commitsSinceCheckpoint;
    private static final ThreadLocal<Tx> CURRENT = new ThreadLocal<>();

    public Database(Path path, int readerCount) {
        this.path = path;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            this.writer = new Tx(open(false));
            Schema.prepare(writer);
            writer.execute("PRAGMA wal_autocheckpoint=0");
            this.readers = new ArrayBlockingQueue<>(readerCount);
            for (int i = 0; i < readerCount; i++) readers.add(new Conn(open(true)));
            this.observer = new Conn(open(true));
            this.checkpointer = new Conn(open(false));
            this.checkpointThread = Thread.ofPlatform().name("campfire-db-checkpointer").daemon().start(this::runCheckpoints);
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("can't open " + path + ": " + e.getMessage(), e);
        }
    }

    public Path path() {
        return path;
    }

    private java.sql.Connection open(boolean readOnly) throws SQLException {
        var properties = new Properties();
        properties.setProperty("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        properties.setProperty("foreign_keys", "true");
        properties.setProperty("journal_mode", "WAL");
        properties.setProperty("synchronous", "NORMAL");
        properties.setProperty("journal_size_limit", "67108864");
        properties.setProperty("cache_size", "2000");
        properties.setProperty("open_mode", String.valueOf(0x00000002 | 0x00000004 | 0x00008000)); // READWRITE | CREATE | NOMUTEX
        var connection = JDBC.createConnection("jdbc:sqlite:" + path.toAbsolutePath(), properties);
        try (var s = connection.createStatement()) {
            if (readOnly) s.execute("PRAGMA query_only = true");
        }
        return connection;
    }

    /// Runs `work` in a write transaction (or joins the current one), then its after-commit work.
    public <T> T write(Work<T> work) {
        Tx current = CURRENT.get();
        if (current != null) return work.run(current);
        writeLock.lock();
        List<Runnable> queued;
        T value;
        try {
            CURRENT.set(writer);
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
            queued = writer.afterCommit;
            writer.afterCommit = new ArrayList<>();
            noteCommit();
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
        } finally {
            CURRENT.remove();
            writer.inTransaction = false;
            writeLock.unlock();
        }
    }

    /// Runs `read` on a read connection, or on the writer when inside a write on this thread.
    public <T> T read(Read<T> read) {
        Tx current = CURRENT.get();
        if (current != null) return read.run(current);
        Conn conn;
        try {
            conn = readers.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DbException("interrupted waiting for a read connection");
        }
        try {
            return read.run(conn);
        } finally {
            readers.add(conn);
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

    private void noteCommit() {
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
            readers.forEach(Conn::close);
            observer.close();
            checkpointer.close();
            writer.close();
        } finally {
            writeLock.unlock();
        }
    }
}
