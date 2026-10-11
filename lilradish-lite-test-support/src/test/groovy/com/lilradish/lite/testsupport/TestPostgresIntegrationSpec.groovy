package com.lilradish.lite.testsupport

import java.nio.file.Files
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class TestPostgresIntegrationSpec extends Specification {
    def "a fixture termination check refuses #operation before reuse even with no open connection"() {
        given:
        def scope = TestPostgres.isolated()
        def finished = new java.util.concurrent.atomic.AtomicBoolean()
        def checks = new java.util.concurrent.atomic.AtomicInteger()
        scope.registerQuiescence {
            checks.incrementAndGet()
            if (!finished.get()) {
                throw new IllegalStateException("The fixture background work has not ended")
            }
        }
        def earlier = scope.postgresDatabase

        when:
        operation == "reset" ? scope.restoreBaseline() : scope.close()

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "The fixture background work has not ended"
        checks.get() == 1
        earlier.connection.withCloseable { !it.closed }

        when:
        finished.set(true)
        scope.restoreBaseline()
        earlier.connection

        then:
        def stale = thrown(SQLException)
        stale.message == "The test datasource belongs to a completed worker scope"
        checks.get() == 2
        scope.postgresDatabase.connection.withCloseable { !it.closed }

        cleanup:
        finished?.set(true)
        scope?.close()

        where:
        operation << ["reset", "close"]
    }

    def "an additional temporary database tracks its held physical connection before scope close"() {
        given:
        def scope = TestPostgres.isolated()
        scope.createDatabase("sensitive", "")
        def source = scope.getDatabase("postgres", "sensitive")
        def held = source.connection

        when:
        scope.close()

        then:
        def failure = thrown(SQLException)
        failure.message == "Connections from the previous test scope are still open"
        !held.closed

        when:
        held.close()
        scope.close()
        source.connection

        then:
        thrown(SQLException)

        cleanup:
        held?.close()
        scope?.close()
    }

    def "ordinary scopes reuse one worker database and restore seed rows without rebuilding the schema"() {
        given:
        def first = TestPostgres.worker()
        def firstName = first.databaseName
        first.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute("create schema app")
                statement.execute("create table app.seed (id int primary key, value text, folded text generated always as (lower(value)) stored)")
                statement.execute("insert into app.seed(id,value) values (1,'Baseline 😀')")
                statement.execute("create table app.z_parent (id int primary key)")
                statement.execute("create table app.a_child (id int primary key, parent_id int not null references app.z_parent)")
                statement.execute("insert into app.z_parent values (11)")
                statement.execute("insert into app.a_child values (12,11)")
            }
        }
        first.rememberBaseline()
        def stale = first.postgresDatabase
        stale.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute("update app.seed set value = 'changed'") }
        }
        first.close()

        when:
        def next = TestPostgres.worker()

        then:
        next.databaseName == firstName
        next.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select id,value,folded from app.seed").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 1
                    assert rows.getString(2) == "Baseline 😀"
                    assert rows.getString(3) == "baseline 😀"
                    assert !rows.next()
                    true
                }
            }
        }
        next.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select child.id, parent.id from app.a_child child join app.z_parent parent on child.parent_id = parent.id").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 12
                    assert rows.getInt(2) == 11
                    assert !rows.next()
                    true
                }
            }
        }

        when:
        first.restoreBaseline()

        then:
        def closedReset = thrown(IllegalStateException)
        closedReset.message == "The test database scope is closed"

        when:
        first.postgresDatabase

        then:
        def closedFactory = thrown(IllegalStateException)
        closedFactory.message == "The test database scope is closed"

        when:
        first.createRole("closed_scope_must_not_create", "")

        then:
        def closedAdministration = thrown(IllegalStateException)
        closedAdministration.message == "The test database scope is closed"
        next.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select id,value,folded from app.seed").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 1
                    assert rows.getString(2) == "Baseline 😀"
                    assert rows.getString(3) == "baseline 😀"
                    assert !rows.next()
                    true
                }
            }
        }

        when:
        stale.connection

        then:
        thrown(SQLException)

        cleanup:
        next?.close()
        first?.close()
    }

    def "an ordinary reset refuses a connection still held by the previous scope"() {
        given:
        def scope = TestPostgres.worker()
        def leaked = scope.postgresDatabase.connection

        when:
        scope.restoreBaseline()

        then:
        def fault = thrown(SQLException)
        fault.message == "Connections from the previous test scope are still open"
        !leaked.closed

        cleanup:
        leaked?.close()
        scope?.close()
    }

    def "a physical acquisition holding the real factory monitor makes reset wait then refuse the held connection"() {
        given:
        def scope = TestPostgres.isolated()
        seedRaceRows(scope)
        def gate = new CountDownLatch(1)
        def entered = new CountDownLatch(1)
        GatedSocketFactory.entered = entered
        GatedSocketFactory.release = gate
        def oldSource = scope.dataSource("?socketFactory=${GatedSocketFactory.name}")
        def connection = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>()
        def borrowFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>()
        def resetFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>()
        def borrower = new Thread({
            try {
                connection.set(oldSource.connection)
            } catch (Throwable failure) {
                borrowFailure.set(failure)
            }
        }, "controlled-physical-acquisition")
        def resetter = new Thread({
            try {
                scope.restoreBaseline()
            } catch (Throwable failure) {
                resetFailure.set(failure)
            }
        }, "controlled-baseline-reset")

        when:
        borrower.start()
        assert entered.await(10, TimeUnit.SECONDS)
        resetter.start()

        then:
        blockedOnDatabaseMonitor(resetter, borrower)
        connection.get() == null
        borrowFailure.get() == null
        resetFailure.get() == null

        when:
        gate.countDown()
        borrower.join(10000)
        resetter.join(10000)

        then:
        !borrower.alive
        !resetter.alive
        borrowFailure.get() == null
        connection.get() != null
        !connection.get().closed
        resetFailure.get() instanceof SQLException
        resetFailure.get().message == "Connections from the previous test scope are still open"
        raceRowsOf(scope) == [[29, "Current race value"]]

        when:
        connection.get().close()
        scope.restoreBaseline()

        then:
        raceRowsOf(scope) == [[17, "Original race value"]]
        scope.postgresDatabase.connection.withCloseable { !it.closed }

        cleanup:
        gate?.countDown()
        borrower?.join(10000)
        resetter?.join(10000)
        connection?.get()?.close()
        GatedSocketFactory.entered = null
        GatedSocketFactory.release = null
        scope?.close()
    }

    def "a reset holding the real database monitor makes an earlier source wait then reject its stale epoch"() {
        given:
        def scope = TestPostgres.isolated()
        seedRaceRows(scope, true)
        def oldSource = scope.postgresDatabase
        def blocker = scope.administrationDatabase.connection
        blocker.autoCommit = false
        def blockerPid = blocker.createStatement().withCloseable { statement ->
            // DB-SPECIFIC: PostgreSQL backend IDs and catalog row locks gate the actual ALTER DATABASE reset.
            statement.executeQuery("select pg_backend_pid()").withCloseable { rows ->
                assert rows.next()
                rows.getInt(1)
            }
        }
        blocker.prepareStatement("select setconfig from pg_db_role_setting where setrole = 0 and setdatabase = (select oid from pg_database where datname = ?) for update").withCloseable { statement ->
            statement.setString(1, scope.databaseName)
            statement.executeQuery().withCloseable { rows ->
                assert rows.next()
                assert rows.getArray(1).array.toList() == ["application_name=controlled race baseline"]
                assert !rows.next()
            }
        }
        def resetFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>()
        def borrowFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>()
        def escaped = new java.util.concurrent.atomic.AtomicReference<java.sql.Connection>()
        def resetter = new Thread({
            try {
                scope.restoreBaseline()
            } catch (Throwable failure) {
                resetFailure.set(failure)
            }
        }, "controlled-reset-before-acquisition")
        def borrower = new Thread({
            try {
                escaped.set(oldSource.connection)
            } catch (Throwable failure) {
                borrowFailure.set(failure)
            }
        }, "controlled-earlier-source")

        when:
        resetter.start()
        assert resetWaitsForCatalogLock(scope, blockerPid)
        borrower.start()

        then:
        blockedOnDatabaseMonitor(borrower, resetter)
        resetFailure.get() == null
        borrowFailure.get() == null
        escaped.get() == null

        when:
        blocker.commit()
        blocker.close()
        resetter.join(10000)
        borrower.join(10000)

        then:
        !resetter.alive
        !borrower.alive
        resetFailure.get() == null
        borrowFailure.get() instanceof SQLException
        borrowFailure.get().message == "The test datasource belongs to a completed worker scope"
        escaped.get() == null
        raceRowsOf(scope) == [[17, "Original race value"]]
        scope.postgresDatabase.connection.withCloseable { !it.closed }

        cleanup:
        if (blocker != null && !blocker.closed) {
            blocker.rollback()
            blocker.close()
        }
        resetter?.join(10000)
        borrower?.join(10000)
        escaped?.get()?.close()
        scope?.close()
    }

    private static void seedRaceRows(TestPostgres scope, boolean setting = false) {
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute("create schema app")
                statement.execute("create table app.race_seed (id int primary key, content text not null)")
                statement.execute("insert into app.race_seed values (17,'Original race value')")
            }
        }
        if (setting) {
            // DB-SPECIFIC: PostgreSQL ALTER DATABASE changes the next connection's startup defaults.
            scope.administrationDatabase.connection.withCloseable { connection ->
                connection.createStatement().withCloseable { it.execute("alter database \"${scope.databaseName}\" set application_name to 'controlled race baseline'") }
            }
        }
        scope.rememberBaseline()
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute("delete from app.race_seed")
                statement.execute("insert into app.race_seed values (29,'Current race value')")
            }
        }
    }

    private static List<List<Object>> raceRowsOf(TestPostgres scope) {
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select id,content from app.race_seed order by id").withCloseable { rows ->
                    def result = []
                    while (rows.next()) {
                        result.add([rows.getInt(1), rows.getString(2)])
                    }
                    result
                }
            }
        }
    }

    private static boolean blockedOnDatabaseMonitor(Thread blocked, Thread owner) {
        def threads = java.lang.management.ManagementFactory.threadMXBean
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            def state = threads.getThreadInfo(blocked.threadId())
            if (state?.threadState == Thread.State.BLOCKED && state.lockOwnerId == owner.threadId()
                    && state.lockInfo.className == TestDatabase.name) {
                return true
            }
            if (!blocked.alive) {
                return false
            }
            Thread.yield()
        }
        false
    }

    private static boolean resetWaitsForCatalogLock(TestPostgres scope, int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        scope.administrationDatabase.connection.withCloseable { connection ->
            // DB-SPECIFIC: PostgreSQL pg_blocking_pids identifies the exact transaction blocking RESET ALL.
            connection.prepareStatement("select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid)) and query = ?").withCloseable { statement ->
                statement.setInt(1, blockerPid)
                statement.setString(2, "alter database \"${scope.databaseName}\" reset all")
                while (System.nanoTime() < deadline) {
                    def found = statement.executeQuery().withCloseable { rows ->
                        assert rows.next()
                        rows.getInt(1) == 1
                    }
                    if (found) {
                        return true
                    }
                    Thread.yield()
                }
                false
            }
        }
    }

    public static class GatedSocketFactory extends javax.net.SocketFactory {
        static volatile CountDownLatch entered
        static volatile CountDownLatch release

        @Override
        java.net.Socket createSocket() {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("The controlled JDBC socket was not released")
            }
            new java.net.Socket()
        }

        @Override
        java.net.Socket createSocket(String host, int port) {
            javax.net.SocketFactory.default.createSocket(host, port)
        }

        @Override
        java.net.Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) {
            javax.net.SocketFactory.default.createSocket(host, port, local, localPort)
        }

        @Override
        java.net.Socket createSocket(java.net.InetAddress host, int port) {
            javax.net.SocketFactory.default.createSocket(host, port)
        }

        @Override
        java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) {
            javax.net.SocketFactory.default.createSocket(host, port, local, localPort)
        }
    }

    def "reset retains every nonempty baseline database setting and removes later settings"() {
        given:
        def scope = TestPostgres.isolated()
        def originalName = "baseline=it's synthetic"
        def originalPath = '"baseline=it\'s synthetic", public'
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute("create schema app")
                statement.execute("create table app.settings_seed (id int primary key)")
                statement.execute("insert into app.settings_seed values (17)")
            }
        }
        scope.administrationDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                // DB-SPECIFIC: PostgreSQL ALTER DATABASE stores defaults used by new physical connections.
                statement.execute("alter database \"${scope.databaseName}\" set application_name to 'baseline=it''s synthetic'")
                statement.execute("alter database \"${scope.databaseName}\" set search_path to \"baseline=it's synthetic\", public")
            }
        }
        assert settingsOf(scope) == ["application_name=${originalName}", "search_path=${originalPath}"]
        scope.rememberBaseline()
        scope.administrationDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                // DB-SPECIFIC: PostgreSQL ALTER DATABASE stores and resets session-setting defaults.
                statement.execute("alter database \"${scope.databaseName}\" set application_name to 'a later fixture'")
                statement.execute("alter database \"${scope.databaseName}\" set idle_session_timeout to '9s'")
                statement.execute("alter database \"${scope.databaseName}\" set search_path to 'public'")
            }
        }
        assert settingsOf(scope) == ["application_name=a later fixture", "idle_session_timeout=9s", "search_path=public"]

        when:
        scope.restoreBaseline()

        then:
        settingsOf(scope) == ["application_name=${originalName}", "search_path=${originalPath}"]
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                // DB-SPECIFIC: PostgreSQL SHOW reads search_path; PGJDBC supplies its own
                // application_name in the startup packet, so search_path
                // independently observes the restored database default through this new connection.
                statement.executeQuery("show search_path").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getString(1) == originalPath
                    assert !rows.next()
                }
                // DB-SPECIFIC: PostgreSQL SHOW observes that the added idle timeout was removed.
                statement.executeQuery("show idle_session_timeout").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getString(1) == "0"
                    assert !rows.next()
                }
                statement.executeQuery("select id from app.settings_seed").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 17
                    assert !rows.next()
                }
                true
            }
        }

        when:
        scope.close()
        def following = TestPostgres.isolated()

        then:
        settingsOf(following).empty
        following.postgresDatabase.connection.withCloseable { !it.closed }

        cleanup:
        following?.close()
        scope?.close()
    }

    def "a later COPY failure rolls back all baseline writes and can be retried after the constraint is removed"() {
        given:
        def scope = TestPostgres.isolated()
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute("create schema app")
                statement.execute("create table app.a_first (id int primary key, content text not null)")
                statement.execute("create table app.z_last (id int primary key, content text not null)")
                statement.execute("insert into app.a_first values (1,'First baseline 😀')")
                statement.execute("insert into app.z_last values (2,'Last baseline 😀')")
            }
        }
        scope.rememberBaseline()
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute("delete from app.a_first")
                statement.execute("delete from app.z_last")
                statement.execute("insert into app.a_first values (31,'First current value'),(32,'Another current value')")
                statement.execute("insert into app.z_last values (41,'Last current value')")
                statement.execute("alter table app.z_last add constraint controlled_baseline_rejection check (id <> 2)")
            }
        }
        assert contentsOf(scope) == [a_first: [[31, "First current value"], [32, "Another current value"]], z_last: [[41, "Last current value"]]]
        def oldSource = scope.postgresDatabase

        when:
        scope.restoreBaseline()

        then:
        def failure = thrown(SQLException)
        failure.message == "The worker baseline could not be restored"
        failure.cause instanceof org.postgresql.util.PSQLException
        failure.cause.SQLState == "23514"
        failure.cause.serverErrorMessage.schema == "app"
        failure.cause.serverErrorMessage.table == "z_last"
        failure.cause.serverErrorMessage.constraint == "controlled_baseline_rejection"
        contentsOf(scope) == [a_first: [[31, "First current value"], [32, "Another current value"]], z_last: [[41, "Last current value"]]]

        when:
        oldSource.connection

        then:
        def stale = thrown(SQLException)
        stale.message == "The test datasource belongs to a completed worker scope"

        when:
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute("alter table app.z_last drop constraint controlled_baseline_rejection") }
        }
        scope.restoreBaseline()

        then:
        contentsOf(scope) == [a_first: [[1, "First baseline 😀"]], z_last: [[2, "Last baseline 😀"]]]
        scope.postgresDatabase.connection.withCloseable { !it.closed }
        scope.close()

        cleanup:
        scope?.close()
    }

    private static List<String> settingsOf(TestPostgres scope) {
        scope.administrationDatabase.connection.withCloseable { connection ->
            // DB-SPECIFIC: PostgreSQL stores database defaults as a complete setconfig array.
            connection.prepareStatement("select unnest(setconfig) from pg_db_role_setting where setrole = 0 and setdatabase = (select oid from pg_database where datname = ?) order by 1").withCloseable { statement ->
                statement.setString(1, scope.databaseName)
                statement.executeQuery().withCloseable { rows ->
                    def values = []
                    while (rows.next()) {
                        values.add(rows.getString(1))
                    }
                    values
                }
            }
        }
    }

    private static Map<String, List<List<Object>>> contentsOf(TestPostgres scope) {
        scope.postgresDatabase.connection.withCloseable { connection ->
            ["a_first", "z_last"].collectEntries { table ->
                connection.createStatement().withCloseable { statement ->
                    statement.executeQuery("select id,content from app.${table} order by id").withCloseable { rows ->
                        def values = []
                        while (rows.next()) {
                            values.add([rows.getInt(1), rows.getString(2)])
                        }
                        [(table): values]
                    }
                }
            }
        }
    }

    def "schema-sensitive scopes have distinct empty databases on the same server"() {
        given:
        def first = TestPostgres.isolated()
        def second = TestPostgres.isolated()

        expect:
        first.port == second.port
        first.databaseName != second.databaseName
        [first, second].every { scope ->
            scope.postgresDatabase.connection.withCloseable { connection ->
                connection.createStatement().withCloseable { statement ->
                    statement.executeQuery("select count(*) from information_schema.tables where table_schema = 'app'").withCloseable { rows ->
                        rows.next()
                        rows.getInt(1) == 0
                    }
                }
            }
        }

        cleanup:
        second?.close()
        first?.close()
    }

    def "a borrowed pooled connection prevents reset until its return and the pool ends before baseline reuse"() {
        given:
        def scope = TestPostgres.isolated()
        seedRaceRows(scope)
        def pool = scope.pooledDataSource(scope.databaseName, "")
        def held = pool.connection

        when:
        scope.restoreBaseline()

        then:
        def borrowed = thrown(SQLException)
        borrowed.message == "Connections from the previous test scope are still borrowed"
        !pool.closed
        !held.closed
        held.createStatement().withCloseable { statement ->
            statement.executeQuery("select id,content from app.race_seed").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 29
                assert rows.getString(2) == "Current race value"
                assert !rows.next()
                true
            }
        }

        when:
        held.close()
        scope.restoreBaseline()

        then:
        pool.closed
        raceRowsOf(scope) == [[17, "Original race value"]]

        cleanup:
        held?.close()
        pool?.close()
        scope?.close()
    }

    def "moving between schema-sensitive cases drops the previous database and scope close removes its unique role"() {
        given:
        def scope = TestPostgres.isolated()
        def observer = TestPostgres.isolated()
        seedRaceRows(scope)
        def role = 'case_' + UUID.randomUUID().toString().replace('-', '') + '"reader'
        scope.createRole(role, "login")
        scope.beginLibraryCase("first", true)
        def firstName = scope.databaseNameFor("first")
        scope.getDatabase(role, "first").connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select current_user").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getString(1) == role
                    assert !rows.next()
                }
            }
        }

        when:
        scope.createDatabase("first", "")

        then:
        def duplicate = thrown(IllegalArgumentException)
        duplicate.message == "The fixture database already exists in this scope"
        scope.databaseNameFor("first") == firstName

        when:
        scope.beginLibraryCase("next", true)
        def nextName = scope.databaseNameFor("next")

        then:
        nextName != firstName
        observer.administrationDatabase.connection.withCloseable { connection ->
            // DB-SPECIFIC: PostgreSQL's cluster catalogue observes that the previous case database was dropped.
            connection.prepareStatement("select count(*) from pg_database where datname = ?").withCloseable { statement ->
                statement.setString(1, firstName)
                statement.executeQuery().withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 0
                    assert !rows.next()
                    true
                }
            }
        }
        scope.getDatabase("postgres", "next").connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select count(*) from information_schema.tables where table_schema = 'app'").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 0
                    assert !rows.next()
                    true
                }
            }
        }

        when:
        scope.beginLibraryCase("ordinary", false)

        then:
        scope.databaseNameFor("ordinary") == scope.databaseName
        raceRowsOf(scope) == [[17, "Original race value"]]

        when:
        scope.close()

        then:
        observer.administrationDatabase.connection.withCloseable { connection ->
            // DB-SPECIFIC: PostgreSQL roles are cluster-wide and pg_database records dropped case databases.
            connection.prepareStatement("select (select count(*) from pg_roles where rolname = ?), (select count(*) from pg_database where datname in (?, ?))").withCloseable { statement ->
                statement.setString(1, role)
                statement.setString(2, firstName)
                statement.setString(3, nextName)
                statement.executeQuery().withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 0
                    assert rows.getInt(2) == 0
                    assert !rows.next()
                    true
                }
            }
        }

        cleanup:
        scope?.close()
        observer?.close()
    }

    def "a populated immediate foreign-key cycle rejects baseline capture without changing rows and a valid order can be retried"() {
        given:
        def scope = TestPostgres.isolated()
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.execute("create schema app")
                statement.execute("create table app.left_rows (id integer primary key, right_id integer not null)")
                statement.execute("create table app.right_rows (id integer primary key, left_id integer not null)")
                statement.execute("insert into app.left_rows values (11,22)")
                statement.execute("insert into app.right_rows values (22,11)")
                statement.execute("alter table app.left_rows add constraint left_needs_right foreign key (right_id) references app.right_rows")
                statement.execute("alter table app.right_rows add constraint right_needs_left foreign key (left_id) references app.left_rows")
            }
        }

        when:
        scope.rememberBaseline()

        then:
        def cycle = thrown(SQLException)
        cycle.message == "The baseline has a non-deferrable foreign-key cycle between tables"
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select l.id,l.right_id,r.id,r.left_id from app.left_rows l join app.right_rows r on r.id=l.right_id").withCloseable { rows ->
                    assert rows.next()
                    assert (1..4).collect { rows.getInt(it) } == [11,22,22,11]
                    assert !rows.next()
                    true
                }
            }
        }

        when:
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute("alter table app.right_rows drop constraint right_needs_left") }
        }
        scope.rememberBaseline()
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute("update app.left_rows set right_id=22; update app.right_rows set left_id=33") }
        }
        scope.restoreBaseline()

        then:
        scope.postgresDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery("select l.id,l.right_id,r.id,r.left_id from app.left_rows l join app.right_rows r on r.id=l.right_id").withCloseable { rows ->
                    assert rows.next()
                    assert (1..4).collect { rows.getInt(it) } == [11,22,22,11]
                    assert !rows.next()
                    true
                }
            }
        }

        cleanup:
        scope?.close()
    }

    def "a present missing or malformed managed descriptor is rejected without starting a fallback"() {
        given:
        def directory = Files.createTempDirectory("invalid-test-descriptor-")
        def descriptor = directory.resolve("endpoint.properties")
        if (exists) {
            Files.writeString(descriptor, "port=not-a-port\ngeneration=invalid\nstarted=not-an-instant\n")
        }

        when:
        TestPostgres.managed(descriptor)

        then:
        thrown(exists ? NumberFormatException : java.nio.file.NoSuchFileException)
        Files.list(directory).withCloseable { it.count() } == (exists ? 1 : 0)

        cleanup:
        Files.deleteIfExists(descriptor)
        Files.deleteIfExists(directory)

        where:
        exists << [false, true]
    }
}
