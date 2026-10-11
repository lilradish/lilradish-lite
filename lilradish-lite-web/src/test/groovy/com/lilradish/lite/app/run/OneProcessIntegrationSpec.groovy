package com.lilradish.lite.app.run

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.lilradish.lite.app.codestep.CodeStepDeployment
import com.lilradish.lite.app.inference.LongestSend
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testsupport.VerifiedDataSource
import com.lilradish.lite.testutil.SnapshottingAppender
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.EngineCodeTasks
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource
import org.postgresql.Driver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.autoconfigure.context.LifecycleProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.support.GenericApplicationContext
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class OneProcessIntegrationSpec extends Specification {

    static final CodeStepDeployment A_SECOND = new CodeStepDeployment(Duration.ofSeconds(1))

    static final Logger PROCESS_LOGGER = LoggerFactory.getLogger(OneProcess) as Logger

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    String database

    List<AutoCloseable> closing = []

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        database = "one_process_" + (++databasesMade)
        store = LibraryStore.forCase(server, database)
        database = server.databaseNameFor(database)
    }

    def cleanup() {
        closing.reverseEach { it.close() }
    }

    // Without a web server, the process lock stops at the default lifecycle phase.
    private static OneProcess taken(DataSource over) {
        new OneProcess(over, new StaticListableBeanFactory().getBeanProvider(LongestSend), A_SECOND,
                new StaticListableBeanFactory().getBeanProvider(LifecycleProperties))
    }

    private List<Integer> holders() {
        // DB-SPECIFIC: PostgreSQL current_database(), pg_database, pg_locks.
        store.session.sql("""
                select held.pid from pg_locks held
                  join pg_database db on db.oid = held.database
                 where held.locktype = 'advisory' and held.granted and db.datname = current_database()
                 order by held.pid
                """).query(Integer).list()
    }

    private static int pidOf(Connection connection) {
        connection.createStatement().withCloseable { statement ->
            // DB-SPECIFIC: PostgreSQL pg_backend_pid.
            statement.executeQuery("select pg_backend_pid()").withCloseable { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private List<Integer> tryingBesides(int holder) {
        // DB-SPECIFIC: PostgreSQL current_database(), pg_stat_activity.
        store.session.sql("""
                select pid from pg_stat_activity
                 where datname = current_database() and pid <> ? and state = 'idle'
                   and query like 'select pg_try_advisory_lock(%'
                """).params(holder).query(Integer).list()
    }

    private long sessions() {
        // DB-SPECIFIC: PostgreSQL current_database(), pg_stat_activity.
        store.count("select count(*) from pg_stat_activity where datname = current_database()")
    }

    private void ended(int pid) {
        // DB-SPECIFIC: PostgreSQL pg_terminate_backend.
        store.session.sql("select pg_terminate_backend(?)").params(pid).query(Boolean).single()
        until("session ${pid} ended") {
            // DB-SPECIFIC: PostgreSQL pg_stat_activity.
            store.count("select count(*) from pg_stat_activity where pid = ?", pid) == 0
        }
    }

    private static void until(String what, Closure<Boolean> reached) {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!reached()) {
            assert System.nanoTime() < deadline: "never ${what}"
            Thread.sleep(10)
        }
    }

    def "a process starting takes the lock at once and sets its session's idle and network timeouts alone"() {
        when:
        def process = taken(store.database)

        then:
        holders() == [pidOf(process.@held)]
        process.@held.createStatement().withCloseable { statement ->
            // DB-SPECIFIC: PostgreSQL string_agg(), pg_settings.
            statement.executeQuery("""
                    select string_agg(name || '=' || setting, ' ' order by name) from pg_settings
                     where name in ('idle_session_timeout', 'tcp_keepalives_idle', 'tcp_keepalives_interval',
                                    'tcp_keepalives_count', 'tcp_user_timeout')
                    """).withCloseable { result ->
                result.next()
                result.getString(1)
            }
        } == "idle_session_timeout=0 tcp_keepalives_count=3 tcp_keepalives_idle=10 tcp_keepalives_interval=5" +
                " tcp_user_timeout=25000"

        and: "for its own session alone"
        // DB-SPECIFIC: PostgreSQL pg_settings.
        store.texts("select setting from pg_settings where name = 'tcp_user_timeout'") == ["0"]

        cleanup:
        process.destroy()
    }

    // A database idle-session timeout must not terminate the process-lock session.
    def "a database ending idle sessions ends another idle one, and never the one holding the lock"() {
        given:
        // DB-SPECIFIC: PostgreSQL ALTER DATABASE.
        store.session.sql("alter database ${database} set idle_session_timeout = '1s'" as String).update()
        def process = taken(store.database)
        def plain = store.database.connection
        closing << plain
        def plainSession = pidOf(plain)

        when:
        until("the plain idle session ended") {
            // DB-SPECIFIC: PostgreSQL pg_stat_activity.
            store.count("select count(*) from pg_stat_activity where pid = ?", plainSession) == 0
        }

        then:
        holders() == [pidOf(process.@held)]

        and:
        !plain.isValid(1)

        cleanup:
        process.destroy()
    }

    def "a second process waits while the first holds the lock, then refuses to start naming only the holder's session and address, keeping nothing open"() {
        given:
        def first = taken(store.database)
        def holder = pidOf(first.@held)
        def before = sessions()

        when:
        def began = System.nanoTime()
        OneProcess.lockedWithin(store.database, Duration.ofSeconds(2))

        then:
        def refused = thrown(IllegalStateException)
        refused.message ==~ /Another process drives runs over this store: session ${holder} from (127\.0\.0\.1|::1)/ +
                / still held advisory lock ${OneProcess.KEY} after PT2S, so this one does not start/
        Duration.ofNanos(System.nanoTime() - began) >= Duration.ofSeconds(2)

        and: "the first still the one holding it, and the second's session gone"
        holders() == [holder]
        until("the refused session ended") { sessions() == before }

        cleanup:
        first.destroy()
    }

    def "a holder's address hidden from another role is never reported as a local socket"() {
        given:
        def observer = "lock_observer_" + database
        // DB-SPECIFIC: PostgreSQL CREATE ROLE LOGIN.
        server.createRole(observer, "login")
        def first = taken(store.database)
        def holder = pidOf(first.@held)
        def before = sessions()
        def observing = server.getDatabase(observer, database)

        when:
        OneProcess.lockedWithin(observing, Duration.ZERO)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Another process drives runs over this store: session ${holder} from an address not shown here" +
                " still held advisory lock ${OneProcess.KEY} after PT0S, so this one does not start"
        holders() == [holder]
        until("the refused session ended") { sessions() == before }

        cleanup:
        first.destroy()
        store.session.sql("drop role " + observer).update()
    }

    def "a second process waiting takes the lock once the first lets it go"() {
        given:
        def first = taken(store.database)
        def holder = pidOf(first.@held)
        def second = CompletableFuture.supplyAsync { OneProcess.lockedWithin(store.database, Duration.ofSeconds(20)) }
        until("the second session tried the lock") { tryingBesides(holder).size() == 1 }
        def waitedFor = !second.done

        when:
        first.destroy()
        Connection taken = second.get(10, TimeUnit.SECONDS)
        closing << taken

        then:
        waitedFor
        holders() == [pidOf(taken)]
    }

    def "a process whose lock remains held elsewhere past its reclaim window exits with a nonzero status"() {
        given:
        def child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").path,
                "-cp", System.getProperty("java.class.path"), LockWatch.name,
                "jdbc:postgresql://localhost:${server.port}/${database}?sslmode=disable&gssEncMode=disable",
                server.generation, server.started.toString()).redirectErrorStream(true).start()
        def holder = new CompletableFuture<Integer>()
        def reading = CompletableFuture.supplyAsync {
            child.inputStream.withReader { reader ->
                def lines = []
                reader.eachLine { line ->
                    lines << line
                    if (line.startsWith("held ")) {
                        holder.complete(Integer.parseInt(line.substring(5)))
                    }
                }
                lines
            }
        }
        ended(holder.get(20, TimeUnit.SECONDS))
        Connection other = OneProcess.lockedWithin(store.database, Duration.ofSeconds(5))
        closing << other
        def before = sessions()

        when:
        def began = System.nanoTime()
        child.outputStream.write(1)
        child.outputStream.flush()

        then:
        child.waitFor(45, TimeUnit.SECONDS)
        child.exitValue() == 1
        Duration.ofNanos(System.nanoTime() - began) >= OneProcess.TAKE_AGAIN_WITHIN
        def output = reading.get(10, TimeUnit.SECONDS)
        output.contains("logged ERROR The lock keeping a second process off this store was lost and could not be taken again;" +
                " this process stops exception=false")
        !output.contains("watch returned")
        holders() == [pidOf(other)]
        until("the refused session ended") { sessions() == before }

        cleanup:
        if (child?.alive) {
            child.destroyForcibly()
            assert child.waitFor(10, TimeUnit.SECONDS)
        }
    }

    def "a watch finishing after a process was destroyed logs no lost lock and returns"() {
        given:
        def process = taken(store.database)
        ended(pidOf(process.@held))
        Connection other = OneProcess.lockedWithin(store.database, Duration.ofSeconds(5))
        closing << other
        process.destroy()
        def context = new GenericApplicationContext()
        assert !context.closed
        def before = sessions()
        def logged = new SnapshottingAppender()
        logged.start()
        PROCESS_LOGGER.addAppender(logged)

        when:
        process.watched(context)

        then:
        noExceptionThrown()
        logged.list == []
        process.@held == null
        holders() == [pidOf(other)]
        sessions() == before

        cleanup:
        PROCESS_LOGGER.detachAppender(logged)
        context.close()
        process.destroy()
    }

    def "a refresh failure waiting for a watch to finish re-locking returns without reporting a lost lock or exiting"() {
        given:
        def child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").path,
                "-cp", System.getProperty("java.class.path"), RefreshFailureWatch.name,
                "jdbc:postgresql://localhost:" + server.port + "/" + database + "?sslmode=disable&gssEncMode=disable",
                server.generation, server.started.toString()).redirectErrorStream(true).start()
        def holder = new CompletableFuture<Integer>()
        def reading = CompletableFuture.supplyAsync {
            child.inputStream.withReader { reader ->
                def lines = []
                reader.eachLine { line ->
                    lines << line
                    if (line.startsWith("held ")) {
                        holder.complete(Integer.parseInt(line.substring(5)))
                    }
                }
                lines
            }
        }
        ended(holder.get(20, TimeUnit.SECONDS))
        Connection other = OneProcess.lockedWithin(store.database, Duration.ofSeconds(5))
        closing << other
        def externalHolder = pidOf(other)
        def before = sessions()

        when:
        child.outputStream.withCloseable { control ->
            control.write((externalHolder.toString() + "\n").getBytes(StandardCharsets.UTF_8))
            control.flush()
        }

        then:
        child.waitFor(50, TimeUnit.SECONDS)
        child.exitValue() == 0
        def output = reading.get(10, TimeUnit.SECONDS)
        output.contains("watch tried re-lock")
        output.contains("refresh blocked in destroy")
        output.contains("refresh failed")
        output.contains("watch returned")
        output.contains("process logs 0")
        !output.any { it.contains("The lock keeping a second process off this store was lost") }
        !output.any { it.contains("SpringApplication.exit") }
        holders() == [externalHolder]
        until("the watch's session ended") { sessions() == before }

        cleanup:
        if (child?.alive) {
            child.destroyForcibly()
            assert child.waitFor(10, TimeUnit.SECONDS)
        }
        child?.outputStream?.close()
        reading?.get(10, TimeUnit.SECONDS)
    }

    def "a lock whose session answers is kept on that session, opening nothing more"() {
        given:
        def process = taken(store.database)
        def session = pidOf(process.@held)
        def before = sessions()

        when:
        def kept = process.kept()

        then:
        kept
        pidOf(process.@held) == session
        holders() == [session]
        sessions() == before

        cleanup:
        process.destroy()
    }

    def "a lock whose session ended is taken again at once on a new session while nobody else holds it"() {
        given:
        def process = taken(store.database)
        def lost = pidOf(process.@held)
        ended(lost)

        when:
        def kept = process.kept()

        then:
        kept
        def again = pidOf(process.@held)
        again != lost
        holders() == [again]

        cleanup:
        process.destroy()
    }

    def "a lock whose session ended is kept on one new session when another holder releases it within the reclaim window"() {
        given:
        def process = taken(store.database)
        ended(pidOf(process.@held))
        Connection other = OneProcess.lockedWithin(store.database, Duration.ofSeconds(5))
        closing << other
        def holder = pidOf(other)
        def before = sessions()
        def keeping = CompletableFuture.supplyAsync { process.kept() }
        until("the one new session tried the lock") { tryingBesides(holder).size() == 1 }
        def retrySession = tryingBesides(holder).first()
        def waitedFor = !keeping.done

        when:
        other.close()
        def kept = keeping.get(10, TimeUnit.SECONDS)

        then:
        waitedFor
        kept
        pidOf(process.@held) == retrySession
        holders() == [retrySession]
        sessions() == before

        cleanup:
        process.destroy()
    }

    def "a lock whose session ended and stays held elsewhere past the reclaim window is not kept, leaving nothing open"() {
        given:
        def process = taken(store.database)
        ended(pidOf(process.@held))
        Connection other = OneProcess.lockedWithin(store.database, Duration.ofSeconds(5))
        closing << other
        def before = sessions()

        when:
        def began = System.nanoTime()
        def kept = process.kept()

        then:
        !kept
        Duration.ofNanos(System.nanoTime() - began) >= OneProcess.TAKE_AGAIN_WITHIN
        process.@held == null
        holders() == [pidOf(other)]
        until("the refused session ended") { sessions() == before }

        cleanup:
        process.destroy()
    }

    // Returning the session to the pool would keep its advisory lock alive.
    def "let go, the lock goes though its connection is handed back to a pool whose session lives on"() {
        given:
        def config = new HikariConfig()
        config.dataSource = server.physicalPoolDataSource(database, "?currentSchema=app", 1)
        config.maximumPoolSize = 1
        def pool = new HikariDataSource(config)
        closing << pool
        def process = taken(pool)
        def pooledSession = pidOf(process.@held)

        when:
        process.destroy()

        then:
        holders() == []

        and: "the very session the lock was taken on still there, idle in the pool"
        pool.hikariPoolMXBean.totalConnections == 1
        pool.connection.withCloseable { pidOf(it) } == pooledSession
    }

    def "let go after its session ended, the lock is found gone with it, said once as that and nothing more"() {
        given:
        def process = taken(store.database)
        ended(pidOf(process.@held))
        def logged = new SnapshottingAppender()
        logged.start()
        PROCESS_LOGGER.addAppender(logged)
        def before = sessions()

        when:
        process.destroy()

        then:
        noExceptionThrown()
        process.@held == null
        holders() == []
        sessions() == before

        and: "said at the level of a thing that happened, with nothing the failure said"
        logged.list*.formattedMessage ==
                ["The lock keeping a second process off this store went with its session, which ended first"]
        logged.list*.level == [Level.INFO]
        logged.list*.throwableProxy == [null]

        cleanup:
        PROCESS_LOGGER.detachAppender(logged)
    }

    // Persist all completed work before releasing the process lock.
    def "in a context the engine's pools drain before the lock is let go, and the lock goes once they have"() {
        given:
        def context = new AnnotationConfigApplicationContext()
        context.registerBean(DataSource, { store.database } as Supplier<DataSource>, new BeanDefinitionCustomizer[0])
        context.registerBean(CodeStepDeployment, { A_SECOND } as Supplier<CodeStepDeployment>,
                new BeanDefinitionCustomizer[0])
        context.registerBean(OneProcess.BEAN, OneProcess, new BeanDefinitionCustomizer[0])
        context.registerBean("engineExecutor", EngineExecutor, new BeanDefinitionCustomizer[0])
        context.refresh()
        def started = new CompletableFuture<Boolean>()
        def finished = new CountDownLatch(1)
        def heldAsItFinished = new CompletableFuture<List<Integer>>()
        def executor = context.getBean(EngineExecutor)
        EngineCodeTasks.run(executor) {
            started.complete(true)
            assert finished.await(10, TimeUnit.SECONDS)
            heldAsItFinished.complete(holders())
        }
        started.get(10, TimeUnit.SECONDS)
        def holder = pidOf(context.getBean(OneProcess).@held)

        when:
        def stopping = CompletableFuture.runAsync { context.close() }
        until("the pools began draining") { executor.stopping() }
        finished.countDown()
        stopping.get(10, TimeUnit.SECONDS)

        then:
        heldAsItFinished.get(1, TimeUnit.SECONDS) == [holder]

        and:
        holders() == []

        cleanup:
        finished.countDown()
        context.close()
    }

    static final class RefreshFailureWatch {

        static void main(String[] arguments) {
            def database = new VerifiedDataSource(new SimpleDriverDataSource(new Driver(), arguments[0], "postgres", ""),
                    arguments[1], java.time.Instant.parse(arguments[2]))
            def process = OneProcessIntegrationSpec.taken(database)
            def context = new GenericApplicationContext()
            def logged = new SnapshottingAppender()
            logged.start()
            OneProcessIntegrationSpec.PROCESS_LOGGER.addAppender(logged)
            def refresh = Thread.currentThread()
            def watched = new CompletableFuture<Boolean>()
            def blocked = new CompletableFuture<Boolean>()
            Thread watching = null
            Thread observing = null
            try {
                context.registerBean(OneProcess.BEAN, OneProcess, { process } as Supplier<OneProcess>,
                        new BeanDefinitionCustomizer[0])
                context.registerBean("failing", Object, {
                    println("held " + OneProcessIntegrationSpec.pidOf(process.@held))
                    System.out.flush()
                    def externalHolder = CompletableFuture.supplyAsync {
                        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine()
                    }.get(20, TimeUnit.SECONDS).toInteger()
                    watching = new Thread({
                        try {
                            process.watched(context)
                            println("watch returned")
                            watched.complete(true)
                        } catch (Throwable watchFailure) {
                            watched.completeExceptionally(watchFailure)
                        }
                    }, "refresh-watch")
                    watching.daemon = true
                    watching.start()
                    database.connection.withCloseable { connection ->
                        // DB-SPECIFIC: PostgreSQL current_database(), pg_stat_activity.
                        connection.prepareStatement("""
                                select count(*) from pg_stat_activity
                                 where datname = current_database() and pid <> ? and state = 'idle'
                                   and query like 'select pg_try_advisory_lock(%'
                                """).withCloseable { statement ->
                            statement.setInt(1, externalHolder)
                            OneProcessIntegrationSpec.until("the watch tried re-lock") {
                                statement.executeQuery().withCloseable { result ->
                                    result.next()
                                    result.getInt(1) == 1
                                }
                            }
                        }
                    }
                    assert !watched.done
                    println("watch tried re-lock")
                    observing = new Thread({
                        try {
                            OneProcessIntegrationSpec.until("refresh blocked in destroy") {
                                refresh.state == Thread.State.BLOCKED && refresh.stackTrace.any { frame ->
                                    frame.className == OneProcess.name && frame.methodName == "destroy"
                                }
                            }
                            assert context.active
                            assert !context.closed
                            assert !watched.done
                            assert process.@watch.isShutdown()
                            println("refresh blocked in destroy")
                            blocked.complete(true)
                        } catch (Throwable observationFailure) {
                            blocked.completeExceptionally(observationFailure)
                        }
                    }, "refresh-observer")
                    observing.daemon = true
                    observing.start()
                    throw new IllegalStateException("Startup refused")
                } as Supplier<Object>, new BeanDefinitionCustomizer[0])
                try {
                    context.refresh()
                    throw new AssertionError("The context unexpectedly started")
                } catch (BeanCreationException startupFailure) {
                    assert startupFailure.beanName == "failing"
                    assert startupFailure.mostSpecificCause instanceof IllegalStateException
                    assert startupFailure.mostSpecificCause.message == "Startup refused"
                    println("refresh failed")
                }
                assert blocked.get(10, TimeUnit.SECONDS)
                assert watched.get(10, TimeUnit.SECONDS)
                assert !context.active
                assert !context.closed
                assert process.@held == null
                assert process.@watch.isShutdown()
                assert process.@watch.awaitTermination(10, TimeUnit.SECONDS)
                assert logged.list == []
                println("process logs 0")
            } finally {
                process.@watch.shutdown()
                watching?.interrupt()
                observing?.interrupt()
                try {
                    try {
                        process.destroy()
                    } finally {
                        context.close()
                    }
                } finally {
                    OneProcessIntegrationSpec.PROCESS_LOGGER.detachAppender(logged)
                    logged.stop()
                    if (watching != null) {
                        watching.join(10000)
                        assert !watching.alive
                    }
                    if (observing != null) {
                        observing.join(10000)
                        assert !observing.alive
                    }
                    assert process.@watch.awaitTermination(10, TimeUnit.SECONDS)
                }
            }
        }
    }

    static final class LockWatch {

        static void main(String[] arguments) {
            def database = new VerifiedDataSource(new SimpleDriverDataSource(new Driver(), arguments[0], "postgres", ""),
                    arguments[1], java.time.Instant.parse(arguments[2]))
            def process = OneProcessIntegrationSpec.taken(database)
            def context = new GenericApplicationContext()
            def logged = new SnapshottingAppender()
            logged.start()
            OneProcessIntegrationSpec.PROCESS_LOGGER.addAppender(logged)
            context.addApplicationListener({ event ->
                if (event instanceof ContextClosedEvent) {
                    logged.list.each { entry ->
                        println("logged ${entry.level} ${entry.formattedMessage} exception=${entry.throwableProxy != null}")
                    }
                }
            } as ApplicationListener)
            context.refresh()
            println("held " + OneProcessIntegrationSpec.pidOf(process.@held))
            System.out.flush()
            System.in.read()
            process.watched(context)
            println("watch returned")
            process.destroy()
            context.close()
        }
    }
}
