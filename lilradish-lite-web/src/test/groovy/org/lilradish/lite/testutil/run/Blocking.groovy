package org.lilradish.lite.testutil.run

import java.sql.Connection
import java.util.concurrent.TimeUnit
import org.lilradish.lite.testutil.library.LibraryStore

/**
 * Which session another waits on, asked of the server rather than guessed: a session merely waiting on some lock
 * proves nothing about whose lock it is.
 */
final class Blocking {

    private Blocking() {}

    /** Until some session of the store's database is waiting on a lock {@code holder} holds. */
    static void untilBlockedBy(LibraryStore store, Connection holder) {
        int pid = holder.createStatement().withCloseable { statement ->
            statement.executeQuery("select pg_backend_pid()").withCloseable { result ->
                result.next()
                result.getInt(1)
            }
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (store.count("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and ?::integer = any(pg_blocking_pids(pid))
                """, pid) < 1) {
            assert System.nanoTime() < deadline: "no session ever waited on a lock session ${pid} holds"
            Thread.sleep(10)
        }
    }
}
