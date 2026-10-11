package com.lilradish.lite.testsupport

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(30)
class VerifiedDataSourceIntegrationSpec extends Specification {
    @Shared @AutoCleanup TestPostgres server = TestPostgres.isolated()

    def "a wrong generation or a previous postmaster birth closes the raw physical connection"() {
        given:
        def connection = server.postgresDatabase.connection
        def identity = identityOf(connection)

        when:
        VerifiedDataSource.verify(connection, staleGeneration ? "not-this-build" : identity[0],
                staleGeneration ? identity[1] : identity[1].minusSeconds(1))

        then:
        def fault = thrown(SQLException)
        fault.message == "The test database is not the instance owned by this build"
        connection.closed

        where:
        staleGeneration << [true, false]
    }

    def "both datasource overloads verify every newly opened physical connection"() {
        given:
        def actual = server.postgresDatabase
        def opened = []
        def delegate = Stub(DataSource) {
            getConnection() >> { actual.connection.tap { opened.add(it) } }
            getConnection(_, _) >> { String user, String password ->
                actual.getConnection(user, password).tap { opened.add(it) }
            }
        }
        def first = actual.connection
        def identity = identityOf(first)
        first.close()
        def wrong = new VerifiedDataSource(delegate, "not-this-build", identity[1])

        when:
        named ? wrong.getConnection("postgres", "") : wrong.connection

        then:
        thrown(SQLException)
        opened.size() == 1
        opened[0].closed

        where:
        named << [false, true]
    }

    def "a restricted login can read the startup identity without marker-table privileges"() {
        given:
        def role = "identity_" + UUID.randomUUID().toString().replace("-", "")
        // DB-SPECIFIC: PostgreSQL cluster-wide LOGIN roles.
        server.administrationDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute("create role ${role} login") }
        }

        when:
        def restricted = server.getDatabase(role, server.databaseName).connection

        then:
        identityOf(restricted)[0].startsWith("lite-test-") || identityOf(restricted)[0].startsWith("lite-ide-")

        cleanup:
        restricted?.close()
        // DB-SPECIFIC: PostgreSQL cluster-wide role cleanup.
        server.administrationDatabase.connection.withCloseable { connection ->
            connection.createStatement().withCloseable { it.execute("drop role ${role}") }
        }
    }

    def "tracking delegates JDBC values and errors and accounts for a successful physical close only once"() {
        given:
        def actual = server.postgresDatabase.connection
        def identity = identityOf(actual)
        def count = new java.util.concurrent.atomic.AtomicInteger()
        def closes = new java.util.concurrent.atomic.AtomicInteger()
        def failure = new SQLException("A controlled driver close failed", "ZZ001")
        def physical = Stub(java.sql.Connection) {
            createStatement() >> { actual.createStatement() }
            getMetaData() >> { actual.metaData }
            getAutoCommit() >> { actual.autoCommit }
            getWarnings() >> null
            nativeSQL("select 1") >> "select 1"
            isClosed() >> { actual.closed }
            close() >> {
                if (closes.getAndIncrement() == 0) {
                    throw failure
                }
                actual.close()
            }
        }
        def source = new VerifiedDataSource(Stub(DataSource) { getConnection() >> physical },
                identity[0], identity[1], { true }, count)
        def tracked = source.connection

        expect:
        count.get() == 1
        tracked.metaData.databaseProductName == "PostgreSQL"
        tracked.autoCommit
        tracked.warnings == null
        tracked.nativeSQL("select 1") == "select 1"
        tracked.createStatement().withCloseable { statement ->
            statement.executeQuery("select 17").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 17
                assert !rows.next()
                true
            }
        }

        when:
        tracked.close()

        then:
        def caught = thrown(SQLException)
        caught.is(failure)
        caught.SQLState == "ZZ001"
        count.get() == 1
        !actual.closed

        when:
        source.connection

        then:
        def blocked = thrown(SQLException)
        blocked.message == "An earlier physical test connection has not been proved closed"
        count.get() == 1

        when:
        tracked.close()
        tracked.close()

        then:
        actual.closed
        tracked.closed
        count.get() == 0
        closes.get() == 3

        cleanup:
        actual?.close()
    }

    def "a tracked transaction keeps the driver's failure and rolls back only its savepoint"() {
        given:
        def tracked = server.postgresDatabase.connection
        // DB-SPECIFIC: PostgreSQL temporary tables belong to the owning physical connection.
        tracked.createStatement().withCloseable {
            it.execute("create temporary table tracked_contract (id integer primary key)")
        }
        tracked.autoCommit = false
        tracked.createStatement().withCloseable { it.executeUpdate("insert into tracked_contract values (1)") }
        def checkpoint = tracked.setSavepoint("before_second_row")
        tracked.createStatement().withCloseable { it.executeUpdate("insert into tracked_contract values (2)") }

        when:
        tracked.createStatement().withCloseable { it.executeUpdate("insert into tracked_contract values (1)") }

        then:
        def duplicate = thrown(SQLException)
        duplicate.SQLState == "23505"
        duplicate.message.contains("tracked_contract_pkey")

        when:
        tracked.rollback(checkpoint)
        tracked.releaseSavepoint(checkpoint)
        tracked.commit()

        then:
        tracked.createStatement().withCloseable { statement ->
            statement.executeQuery("select id from tracked_contract order by id").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 1
                assert !rows.next()
                true
            }
        }

        cleanup:
        tracked?.close()
    }

    def "aborting a real tracked connection retains its accounting until an acknowledged close"() {
        given:
        def actual = server.postgresDatabase
        def identity = actual.connection.withCloseable { identityOf(it) }
        def count = new java.util.concurrent.atomic.AtomicInteger()
        def source = new VerifiedDataSource(actual, identity[0], identity[1], { true }, count)
        def tracked = source.connection
        def backend = backendOf(tracked)

        when:
        tracked.abort({ Runnable command -> command.run() } as java.util.concurrent.Executor)

        then:
        tracked.closed
        count.get() == 1

        when:
        tracked.close()
        tracked.close()

        then:
        count.get() == 0
        !backendAlive(backend)
        source.connection.withCloseable { replacement ->
            replacement.createStatement().withCloseable { statement ->
                statement.executeQuery("select 41").withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 41
                    assert !rows.next()
                    true
                }
            }
        }
        count.get() == 0

        cleanup:
        tracked?.close()
    }

    def "a physical pool replacement waits for the previous backend and close does not need the acquisition monitor"() {
        given:
        def actual = server.postgresDatabase
        def identity = actual.connection.withCloseable { identityOf(it) }
        def opened = new java.util.concurrent.atomic.AtomicInteger()
        def source = new VerifiedDataSource(Stub(DataSource) {
            getConnection() >> {
                opened.incrementAndGet()
                actual.connection
            }
        }, identity[0], identity[1], 1)
        def first = source.connection
        def backend = backendOf(first)
        def result = new java.util.concurrent.CompletableFuture<java.sql.Connection>()
        def waiting = new Thread({
            try {
                result.complete(source.connection)
            } catch (Throwable fault) {
                result.completeExceptionally(fault)
            }
        }, "physical-pool-replacement")

        when:
        waiting.start()
        awaitPermitWait(waiting)

        then:
        !result.done
        opened.get() == 1
        backendAlive(backend)

        when:
        first.close()
        def replacement = result.get(10, java.util.concurrent.TimeUnit.SECONDS)
        waiting.join(10000)

        then:
        !waiting.alive
        !backendAlive(backend)
        opened.get() == 2
        replacement.createStatement().withCloseable {
            it.executeQuery("select 31").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 31
                assert !rows.next()
                true
            }
        }

        cleanup:
        first?.close()
        if (result.done && !result.completedExceptionally) result.get().close()
        waiting?.join(10000)
    }

    def "a physical pool deadline opens no backend and leaves the held permit intact until its real close"() {
        given:
        def actual = server.postgresDatabase
        def identity = actual.connection.withCloseable { identityOf(it) }
        def opened = new java.util.concurrent.atomic.AtomicInteger()
        def source = new VerifiedDataSource(Stub(DataSource) {
            getConnection() >> {
                opened.incrementAndGet()
                actual.connection
            }
        }, identity[0], identity[1], 1)
        def held = source.connection
        def backend = backendOf(held)
        java.sql.Connection replacement = null
        def result = new java.util.concurrent.CompletableFuture<java.sql.Connection>()
        def waiting = new Thread({
            try {
                result.complete(source.connection)
            } catch (Throwable failure) {
                result.completeExceptionally(failure)
            }
        }, "physical-permit-after-deadline")
        long started = System.nanoTime()

        when:
        source.connection

        then:
        def fault = thrown(SQLException)
        fault.message == "The previous physical pool connection has not finished closing"
        System.nanoTime() - started >= java.util.concurrent.TimeUnit.SECONDS.toNanos(9)
        opened.get() == 1
        !held.closed
        backendAlive(backend)
        held.createStatement().withCloseable { statement ->
            statement.executeQuery("select 17").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 17
                assert !rows.next()
                true
            }
        }

        when:
        waiting.start()
        awaitPermitWait(waiting)

        then:
        !result.done
        opened.get() == 1
        !held.closed
        backendAlive(backend)

        when:
        held.close()
        replacement = result.get(10, java.util.concurrent.TimeUnit.SECONDS)
        waiting.join(10000)

        then:
        !waiting.alive
        !backendAlive(backend)
        opened.get() == 2
        replacement.createStatement().withCloseable { statement ->
            statement.executeQuery("select 31").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 31
                assert !rows.next()
                true
            }
        }

        cleanup:
        held?.close()
        waiting?.join(10000)
        if (result.done && !result.completedExceptionally) result.get().close()
    }

    def "capacity refusal returns a pool permit without opening a backend and #named acquisition succeeds after the holder closes"() {
        given:
        def actual = server.postgresDatabase
        def identity = actual.connection.withCloseable { identityOf(it) }
        def budget = new VerifiedDataSource.Capacity(1)
        def count = new java.util.concurrent.atomic.AtomicInteger()
        def unfinished = new java.util.concurrent.atomic.AtomicInteger()
        def monitor = new Object()
        def holderSource = new VerifiedDataSource(actual, identity[0], identity[1], { true }, count,
                monitor, null, unfinished, budget, server.databaseName)
        def opened = new java.util.concurrent.atomic.AtomicInteger()
        def delegate = Stub(DataSource) {
            getConnection() >> {
                opened.incrementAndGet()
                actual.connection
            }
            getConnection(_, _) >> { String user, String password ->
                opened.incrementAndGet()
                actual.getConnection(user, password)
            }
        }
        def pooled = new VerifiedDataSource(delegate, identity[0], identity[1], { true }, count,
                monitor, VerifiedDataSource.slots(1), unfinished, budget, server.databaseName)
        def held = holderSource.connection
        def backend = backendOf(held)
        Connection replacement = null

        when:
        named ? pooled.getConnection("postgres", "") : pooled.connection

        then:
        def exhausted = thrown(SQLException)
        exhausted.message == "The worker's ordinary physical connection capacity is already in use"
        opened.get() == 0
        count.get() == 1
        backendAlive(backend)
        !held.closed
        held.createStatement().withCloseable { statement ->
            statement.executeQuery("select 37").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 37
                assert !rows.next()
                true
            }
        }

        when:
        held.close()
        replacement = named ? pooled.getConnection("postgres", "") : pooled.connection

        then:
        !backendAlive(backend)
        opened.get() == 1
        count.get() == 1
        unfinished.get() == 0
        replacement.createStatement().withCloseable { statement ->
            statement.executeQuery("select 43").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 43
                assert !rows.next()
                true
            }
        }

        when:
        replacement.close()
        replacement.close()

        then:
        count.get() == 0
        unfinished.get() == 0
        opened.get() == 1

        cleanup:
        replacement?.close()
        held?.close()

        where:
        named << [false, true]
    }

    def "ordinary physical claims are shared across databases and restoring one releases none from another"() {
        given:
        def first = TestPostgres.isolated()
        def other = TestPostgres.isolated()
        def firstSource = first.postgresDatabase
        def otherSource = other.postgresDatabase
        def held = (1..15).collect { firstSource.connection }
        def otherHeld = otherSource.connection

        when:
        firstSource.connection

        then:
        def full = thrown(SQLException)
        full.message == "The worker's ordinary physical connection capacity is already in use"
        held.every { !it.closed }
        !otherHeld.closed
        // DB-SPECIFIC: PostgreSQL's client-backend catalogue measures both real target databases.
        first.administrationDatabase.connection.withCloseable { connection ->
            connection.prepareStatement("select count(*) from pg_stat_activity where datname in (?, ?)")
                    .withCloseable { statement ->
                statement.setString(1, first.databaseName)
                statement.setString(2, other.databaseName)
                statement.executeQuery().withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 16
                    assert !rows.next()
                    true
                }
            }
        }

        when:
        held.each { it.close() }
        held.clear()
        first.restoreBaseline()
        held.addAll((1..15).collect { first.postgresDatabase.connection })
        first.postgresDatabase.connection

        then:
        def stillFull = thrown(SQLException)
        stillFull.message == "The worker's ordinary physical connection capacity is already in use"
        !otherHeld.closed
        otherHeld.createStatement().withCloseable { statement ->
            statement.executeQuery("select 41").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 41
                assert !rows.next()
                true
            }
        }

        when:
        otherHeld.close()
        def replacement = first.postgresDatabase.connection

        then:
        replacement.createStatement().withCloseable { statement ->
            statement.executeQuery("select 43").withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 43
                assert !rows.next()
                true
            }
        }

        cleanup:
        replacement?.close()
        held?.each { it.close() }
        otherHeld?.close()
        other?.close()
        first?.close()
    }

    def "interrupting a physical pool wait opens no backend and does not release another connection's permit"() {
        given:
        def actual = server.postgresDatabase
        def identity = actual.connection.withCloseable { identityOf(it) }
        def opened = new java.util.concurrent.atomic.AtomicInteger()
        def source = new VerifiedDataSource(Stub(DataSource) {
            getConnection() >> {
                opened.incrementAndGet()
                actual.connection
            }
        }, identity[0], identity[1], 1)
        def held = source.connection
        def backend = backendOf(held)
        def next
        def following = new java.util.concurrent.CompletableFuture<Connection>()
        def stillWaiting = new Thread({
            try {
                following.complete(source.connection)
            } catch (Throwable failure) {
                following.completeExceptionally(failure)
            }
        }, "physical-pool-after-interruption")
        def result = new java.util.concurrent.CompletableFuture<List>()
        def waiting = new Thread({
            try {
                source.connection.withCloseable { result.complete([null, false]) }
            } catch (SQLException fault) {
                result.complete([fault, Thread.currentThread().interrupted])
            }
        }, "interrupted-physical-pool-wait")

        when:
        waiting.start()
        awaitPermitWait(waiting)
        waiting.interrupt()
        def refused = result.get(10, java.util.concurrent.TimeUnit.SECONDS)
        waiting.join(10000)

        then:
        refused[0] instanceof SQLException
        refused[0].message == "Interrupted while waiting for a physical pool connection"
        refused[0].cause instanceof InterruptedException
        refused[1]
        opened.get() == 1
        !held.closed
        !waiting.alive
        backendAlive(backend)

        when:
        stillWaiting.start()
        awaitPermitWait(stillWaiting)

        then:
        !following.done
        opened.get() == 1
        !held.closed
        backendAlive(backend)

        when:
        held.close()
        next = following.get(10, java.util.concurrent.TimeUnit.SECONDS)
        stillWaiting.join(10000)
        def read = next.createStatement().withCloseable { statement ->
            statement.executeQuery("select 31").withCloseable { rows ->
                assert rows.next()
                def value = rows.getInt(1)
                assert !rows.next()
                value
            }
        }
        next.close()

        then:
        read == 31
        opened.get() == 2
        next.closed
        !stillWaiting.alive

        cleanup:
        next?.close()
        held?.close()
        waiting?.interrupt()
        waiting?.join(10000)
        stillWaiting?.interrupt()
        stillWaiting?.join(10000)
    }

    def "a verified raw connection without a scope counter waits for its own backend to end"() {
        given:
        def raw = server.postgresDatabase.connection
        def identity = identityOf(raw)
        def backend = backendOf(raw)
        def verified = VerifiedDataSource.verify(raw, identity[0], identity[1])

        expect:
        backendAlive(backend)

        when:
        verified.close()
        verified.close()

        then:
        verified.closed
        !backendAlive(backend)

        cleanup:
        raw?.close()
    }

    def "two failed physical closes keep separate tickets until each exact connection is successfully closed"() {
        given:
        def actual = server.postgresDatabase
        def identity = actual.connection.withCloseable { identityOf(it) }
        def failed = new java.util.concurrent.atomic.AtomicInteger()
        def count = new java.util.concurrent.atomic.AtomicInteger()
        def monitor = new Object()
        def raw = (1..2).collect { actual.connection }
        def attempts = (1..2).collect { new java.util.concurrent.atomic.AtomicInteger() }
        def physical = (0..1).collect { at ->
            Stub(java.sql.Connection) {
                createStatement() >> { raw[at].createStatement() }
                close() >> {
                    if (attempts[at].getAndIncrement() == 0) throw new SQLException("Controlled close ${at}", "ZZ001")
                    raw[at].close()
                }
            }
        }
        def sources = (0..1).collect { at ->
            new VerifiedDataSource(Stub(DataSource) { getConnection() >> physical[at] },
                    identity[0], identity[1], { true }, count, monitor, null, failed)
        }
        def held = sources.collect { it.connection }

        when:
        held[0].close()

        then:
        thrown(SQLException)
        failed.get() == 1
        count.get() == 2

        when:
        held[1].close()

        then:
        thrown(SQLException)
        failed.get() == 2
        count.get() == 2

        when:
        held[0].close()
        sources[0].connection

        then:
        def blocked = thrown(SQLException)
        blocked.message == "An earlier physical test connection has not been proved closed"
        failed.get() == 1
        count.get() == 1
        attempts[0].get() == 2

        when:
        held[1].close()
        held[1].close()

        then:
        failed.get() == 0
        count.get() == 0

        cleanup:
        held?.each { it.close() }
        raw?.each { it.close() }
    }

    def "an unverified physical opening cannot be reclaimed from database zero and reset remains closed"() {
        given:
        def identity = server.postgresDatabase.connection.withCloseable { identityOf(it) }
        def endpoint = new TestPostgres.Endpoint(server.port, identity[0], identity[1])
        def scope = new TestDatabase(endpoint, "unknown_claim_" + UUID.randomUUID().toString().replace("-", ""))
        scope.create("")
        def control = null
        scope.dataSource("postgres", "").connection.withCloseable { connection ->
            connection.createStatement().withCloseable {
                it.execute('create schema app; create table app.kept (id integer primary key); insert into app.kept values (17)')
            }
        }
        scope.remember()
        def missing = "missing_" + UUID.randomUUID().toString().replace("-", "")
        def untracked = null

        when:
        scope.dataSource(missing, "").connection

        then:
        def original = thrown(SQLException)
        original.SQLState == "28000"

        when:
        scope.dataSource("postgres", "").connection

        then:
        def blocked = thrown(SQLException)
        blocked.message == "An earlier physical test connection has not been proved closed"

        when:
        def rawSource = endpoint.physicalSource("postgres", scope.name(), "")
        untracked = java.sql.DriverManager.getConnection(rawSource.URL, "postgres", "")
        scope.restore()

        then:
        def stillOpen = thrown(SQLException)
        stillOpen.message == "Closed test connections have not finished on the server"
        untracked.createStatement().withCloseable { statement ->
            statement.executeQuery('select id from app.kept').withCloseable { rows ->
                assert rows.next()
                assert rows.getInt(1) == 17
                assert !rows.next()
                true
            }
        }

        when:
        scope.dataSource("postgres", "").connection

        then:
        def stillBlocked = thrown(SQLException)
        stillBlocked.message == "An earlier physical test connection has not been proved closed"

        when:
        untracked.close()
        scope.restore()

        then:
        def unknown = thrown(SQLException)
        unknown.message == "An unverified physical opening has no provable backend exit"

        when:
        scope.dataSource("postgres", "").connection

        then:
        def stillUnverified = thrown(SQLException)
        stillUnverified.message == "An earlier physical test connection has not been proved closed"
        endpoint.physicalSource("postgres", scope.name(), "").connection.withCloseable { raw ->
            VerifiedDataSource.verify(raw, identity[0], identity[1]).withCloseable { connection ->
                connection.createStatement().withCloseable { statement ->
                    statement.executeQuery('select id from app.kept').withCloseable { rows ->
                        assert rows.next()
                        assert rows.getInt(1) == 17
                        assert !rows.next()
                        true
                    }
                }
            }
        }

        when:
        control = new TestDatabase(endpoint, "known_control_" + UUID.randomUUID().toString().replace("-", ""))
        control.create("")

        then:
        control.dataSource("postgres", "").connection.withCloseable { connection ->
            connection.createStatement().withCloseable { statement ->
                statement.executeQuery('select 31').withCloseable { rows ->
                    assert rows.next()
                    assert rows.getInt(1) == 31
                    assert !rows.next()
                    true
                }
            }
        }

        cleanup:
        untracked?.close()
        control?.drop()
        scope?.drop()
    }

    def "separate administration factories retain an endpoint's unverified physical opening"() {
        given:
        def identity = server.postgresDatabase.connection.withCloseable { identityOf(it) }
        def endpoint = new TestPostgres.Endpoint(server.port, identity[0], identity[1])
        def missing = "missing_" + UUID.randomUUID().toString().replace("-", "")

        when:
        endpoint.dataSource(missing, "postgres", "").connection

        then:
        def original = thrown(SQLException)
        original.SQLState == "28000"

        when:
        endpoint.dataSource("postgres", "postgres", "").connection

        then:
        def refused = thrown(SQLException)
        refused.message == "An earlier physical test connection has not been proved closed"

        and: "the refusal concerns this endpoint state rather than a stopped server"
        server.administrationDatabase.connection.withCloseable { connection ->
            identityOf(connection) == identity
        }
    }

    private static void awaitPermitWait(Thread waiting) {
        def threads = java.lang.management.ManagementFactory.threadMXBean
        def until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            def info = threads.getThreadInfo(waiting.id)
            if (info?.threadState == Thread.State.TIMED_WAITING &&
                    info.lockInfo?.className?.startsWith("java.util.concurrent.Semaphore\$")) return
            assert waiting.alive
            Thread.yield()
        }
        throw new AssertionError("The physical acquisition did not wait on its real pool permit")
    }

    private static List backendOf(java.sql.Connection connection) {
        // DB-SPECIFIC: PostgreSQL backend identity observed before closing this exact connection.
        connection.createStatement().withCloseable { statement ->
            statement.executeQuery("select pg_backend_pid()").withCloseable { rows ->
                assert rows.next()
                def process = ProcessHandle.of(rows.getLong(1)).orElseThrow()
                [process, process.info().startInstant().orElseThrow()]
            }
        }
    }

    private static boolean backendAlive(List backend) {
        backend[0].alive && backend[0].info().startInstant().filter { it == backend[1] }.present
    }

    private static List identityOf(java.sql.Connection connection) {
        // DB-SPECIFIC: PostgreSQL cluster_name and postmaster startup identity.
        connection.createStatement().withCloseable { statement ->
            statement.executeQuery("select current_setting('cluster_name'), pg_postmaster_start_time()").withCloseable { rows ->
                assert rows.next()
                [rows.getString(1), rows.getTimestamp(2).toInstant()]
            }
        }
    }
}
