package org.lilradish.lite.app.run;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.codestep.CodeStepDeployment;
import org.lilradish.lite.app.inference.LongestSend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One process drives runs over a store at a time. It takes a lock on the store before anything settles what a stopped
 * process left out, and lets it go only once its own runs under way are written down, the engine's pools being
 * destroyed first. Another process holding it is waited for as long as it may take to stop in order; then this one
 * refuses to start, naming who holds it. A lock found lost, and held elsewhere, stops this process.
 */
@Component(OneProcess.BEAN)
final class OneProcess implements DisposableBean, ApplicationContextAware {

    /** Named so the engine's pools are ordered after it without taking it, as they take nothing but the store. */
    static final String BEAN = "oneProcess";

    private static final Logger logger = LoggerFactory.getLogger(OneProcess.class);

    // DB-SPECIFIC: set_config, these settings, session advisory locks, pg_locks and pg_stat_activity are PostgreSQL's.
    /* Held for the process's life the session idles, which an idle timeout would end, lock and all; and the server
     * notices a client gone within about 25 s, so the lock of a process that died goes that soon. */
    private static final String KEPT_ALIVE = """
            select set_config('idle_session_timeout', '0', false), set_config('tcp_keepalives_idle', '10', false),
                   set_config('tcp_keepalives_interval', '5', false), set_config('tcp_keepalives_count', '3', false),
                   set_config('tcp_user_timeout', '25000', false)
            """;

    private static final String TAKE = "select pg_try_advisory_lock(?)";

    private static final String LET_GO = "select pg_advisory_unlock(?)";

    /* A bigint key reads as its two halves, in classid and objid, with objsubid 1. */
    private static final String HOLDER = """
            select activity.pid, host(activity.client_addr) as client_addr
              from pg_locks held
              join pg_stat_activity activity on activity.pid = held.pid
             where held.locktype = 'advisory' and held.granted and held.objsubid = 1
               and held.database = (select db.oid from pg_database db where db.datname = current_database())
               and ((cast(held.classid as bigint) << 32) | cast(held.objid as bigint)) = ?
            """;

    /* Any number would do, advisory locks being per database; pg_locks shows it split in two, as HOLDER reads it. */
    private static final long KEY = 0x6c69_6c72_6164_6973L;

    private static final Duration RETRY = Duration.ofSeconds(1);

    private static final Duration WATCHED_EVERY = Duration.ofSeconds(10);

    private static final int VALID_WITHIN_SECONDS = 5;

    /* Not zero: whatever manages the process starts it again, and must not read this as a stop in order. */
    private static final int LOST = 1;

    private final DataSource store;

    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread watching = new Thread(task, "one-process-watch");
        watching.setDaemon(true);
        return watching;
    });

    private @Nullable Connection held;

    OneProcess(
            DataSource store,
            ObjectProvider<LongestSend> longestSend,
            CodeStepDeployment codeSteps,
            ObjectProvider<LifecycleProperties> lifecycle)
            throws SQLException {
        this.store = store;
        // A process stopping in order spends one shutdown phase on its web server's graceful stop before it drains.
        Duration webStop = lifecycle.getIfAvailable(LifecycleProperties::new).getTimeoutPerShutdownPhase();
        held = lockedWithin(store, EngineExecutor.drain(longestSend, codeSteps).plus(webStop));
    }

    /**
     * A connection of the store's holding the lock, taken as soon as nobody else holds it within {@code wait}; held
     * elsewhere past that, this refuses naming the session holding it, having closed the connection it borrowed.
     */
    static Connection lockedWithin(DataSource store, Duration wait) throws SQLException {
        long deadline = System.nanoTime() + wait.toNanos();
        Connection connection = store.getConnection();
        try {
            // Left in a transaction, a timeout on idle transactions would end the session and the lock with it.
            connection.setAutoCommit(true);
            try (PreparedStatement keeping = connection.prepareStatement(KEPT_ALIVE)) {
                keeping.execute();
            }
            while (!locked(connection, TAKE)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new IllegalStateException("Another process drives runs over this store: " + holder(connection)
                            + " still held advisory lock " + KEY + " after " + wait + ", so this one does not start");
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(left, RETRY.toNanos()));
            }
            return connection;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            connection.close();
            throw new IllegalStateException("Interrupted while waiting for another process to stop driving runs");
        } catch (SQLException | RuntimeException failed) {
            connection.close();
            throw failed;
        }
    }

    private static boolean locked(Connection connection, String statement) throws SQLException {
        try (PreparedStatement asking = connection.prepareStatement(statement)) {
            asking.setLong(1, KEY);
            try (ResultSet answered = asking.executeQuery()) {
                answered.next();
                return answered.getBoolean(1);
            }
        }
    }

    /* Only its process id and where it connects from: nothing else a session says is this system's to repeat. */
    private static String holder(Connection connection) throws SQLException {
        try (PreparedStatement asking = connection.prepareStatement(HOLDER)) {
            asking.setLong(1, KEY);
            try (ResultSet answered = asking.executeQuery()) {
                if (!answered.next()) {
                    return "a session gone since";
                }
                String from = answered.getString("client_addr");
                return "session " + answered.getInt("pid") + " from " + (from == null ? "a local socket" : from);
            }
        }
    }

    /** Watched only in an application, whose context is what a lock lost for good closes. */
    @Override
    public void setApplicationContext(ApplicationContext context) {
        long every = WATCHED_EVERY.toMillis();
        watch.scheduleWithFixedDelay(() -> watched(context), every, every, TimeUnit.MILLISECONDS);
    }

    private void watched(ApplicationContext context) {
        // Once closing, the lock goes with the drain; exiting from here then would only race that close.
        if ((context instanceof ConfigurableApplicationContext closing && closing.isClosed()) || kept()) {
            return;
        }
        logger.error("The lock keeping a second process off this store was lost and could not be taken again; this"
                + " process stops");
        // Closing publishes the close first, which is what says stopping to the engine's pools, and then drains them.
        System.exit(SpringApplication.exit(context, () -> LOST));
    }

    /**
     * Whether this process holds the lock still: its session answering, or, where it no longer does, the lock taken
     * again at once on a new connection. Where it is not held, no connection is left open for it.
     */
    synchronized boolean kept() {
        Connection current = held;
        if (current == null) {
            return false;
        }
        if (answers(current)) {
            return true;
        }
        discarded(current);
        held = null;
        try {
            held = lockedWithin(store, Duration.ZERO);
            return true;
        } catch (SQLException | IllegalStateException refused) {
            return false;
        }
    }

    private static boolean answers(Connection connection) {
        try {
            return connection.isValid(VALID_WITHIN_SECONDS);
        } catch (SQLException ignored) {
            // One that cannot even say whether it is valid is as dead as one that says it is not.
            return false;
        }
    }

    /*
     * Ended rather than handed back: a session slow to answer may be alive still and holding the lock, which a pool
     * would keep open. A pool evicts what was aborted under it as it is closed.
     */
    private static void discarded(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException ignored) {
            // Aborted already, or dead: closing it below is all that is left either way.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closed already, or dead: either way it holds nothing.
        }
    }

    // Handed back to a pool, the session would live on and the lock with it, so the lock is let go first.
    @Override
    public synchronized void destroy() throws SQLException {
        watch.shutdown();
        Connection current = held;
        if (current == null) {
            return;
        }
        held = null;
        try {
            locked(current, LET_GO);
        } catch (SQLException failed) {
            // Asked before it is ended, which would make any connection read as dead. A pool's own warn that it
            // marked the connection broken is expected here, and says no more than this does.
            boolean alive = answers(current);
            discarded(current);
            if (alive) {
                throw failed;
            }
            logger.info("The lock keeping a second process off this store went with its session, which ended first");
            return;
        }
        current.close();
    }
}
