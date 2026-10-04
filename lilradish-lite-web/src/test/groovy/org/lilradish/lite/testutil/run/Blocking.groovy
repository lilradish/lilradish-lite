package org.lilradish.lite.testutil.run

import java.sql.Connection
import java.util.concurrent.TimeUnit
import org.lilradish.lite.testutil.library.LibraryStore

// Check which session holds the blocking lock; merely waiting on any lock proves nothing.
final class Blocking {

    private Blocking() {}

    static void untilBlockedBy(LibraryStore store, Connection holder) {
        int pid = holder.createStatement().withCloseable { statement ->
            // DB-SPECIFIC: PostgreSQL pg_backend_pid.
            statement.executeQuery("select pg_backend_pid()").withCloseable { result ->
                result.next()
                result.getInt(1)
            }
        }
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        // DB-SPECIFIC: PostgreSQL ::integer casts, current_database(), pg_blocking_pids, pg_stat_activity.
        while (store.count("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and ?::integer = any(pg_blocking_pids(pid))
                """, pid) < 1) {
            assert System.nanoTime() < deadline: "no session ever waited on a lock session ${pid} holds"
            Thread.sleep(10)
        }
    }
}
