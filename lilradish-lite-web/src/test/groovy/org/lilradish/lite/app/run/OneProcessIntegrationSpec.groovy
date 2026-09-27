package org.lilradish.lite.app.run

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource
import org.lilradish.lite.app.codestep.CodeStepDeployment
import org.lilradish.lite.app.inference.LongestSend
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.library.LibraryStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.autoconfigure.context.LifecycleProperties
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * One process drives runs over a store at a time, on a real server: the lock taken as the process starts on a session
 * kept alive, a second process waiting on it and then refusing to start naming who holds it, a lock whose session
 * ended taken again or found held elsewhere, and the lock let go only after the engine's pools have drained, whether
 * its connection is closed or handed back to a pool whose session lives on.
 */
class OneProcessIntegrationSpec extends Specification {

    static final CodeStepDeployment A_SECOND = new CodeStepDeployment(Duration.ofSeconds(1))

    static final Logger PROCESS_LOGGER = LoggerFactory.getLogger(OneProcess) as Logger

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    String database

    List<AutoCloseable> closing = []

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        database = "one_process_" + (++databasesMade)
        store = LibraryStore.copied(server, database)
    }

    def cleanup() {
        closing.reverseEach { it.close() }
    }

    /** Taken as a process outside any application takes it: no web server, so its stop at the default phase. */
    private static OneProcess taken(DataSource over) {
        new OneProcess(over, new StaticListableBeanFactory().getBeanProvider(LongestSend), A_SECOND,
                new StaticListableBeanFactory().getBeanProvider(LifecycleProperties))
    }

    /** Which sessions of this database hold an advisory lock, by their process id. */
    private List<Integer> holders() {
        store.session.sql("""
                select held.pid from pg_locks held
                  join pg_database db on db.oid = held.database
                 where held.locktype = 'advisory' and held.granted and db.datname = current_database()
                 order by held.pid
                """).query(Integer).list()
    }

    private static int pidOf(Connection connection) {
        connection.createStatement().withCloseable { statement ->
            statement.executeQuery("select pg_backend_pid()").withCloseable { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private long sessions() {
        store.count("select count(*) from pg_stat_activity where datname = current_database()")
    }

    private void ended(int pid) {
        store.session.sql("select pg_terminate_backend(?)").params(pid).query(Boolean).single()
        until("session ${pid} ended") {
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

    def "a process starting takes the lock at once, on a session kept from timing out idle and ended soon once its client is gone"() {
        when:
        def process = taken(store.database)

        then:
        holders() == [pidOf(process.@held)]
        process.@held.createStatement().withCloseable { statement ->
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
        store.texts("select setting from pg_settings where name = 'tcp_user_timeout'") == ["0"]

        cleanup:
        process.destroy()
    }

    /** A timeout on idle sessions set for the whole database would end the lock's session, and let a second in. */
    def "a database ending idle sessions ends another idle one, and never the one holding the lock"() {
        given:
        store.session.sql("alter database ${database} set idle_session_timeout = '1s'" as String).update()
        def process = taken(store.database)
        def plain = store.database.connection
        closing << plain

        when:
        Thread.sleep(3000)

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

    def "a second process waiting takes the lock once the first lets it go"() {
        given:
        def first = taken(store.database)
        def second = CompletableFuture.supplyAsync { OneProcess.lockedWithin(store.database, Duration.ofSeconds(20)) }
        Thread.sleep(1500)
        def waitedFor = !second.done

        when:
        first.destroy()
        Connection taken = second.get(10, TimeUnit.SECONDS)
        closing << taken

        then:
        waitedFor
        holders() == [pidOf(taken)]
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

    /** A session ended by the server, as a restart of it or a network gone would, takes its lock with it. */
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

    def "a lock whose session ended and that another process took meanwhile is not kept, and nothing is left open for it"() {
        given:
        def process = taken(store.database)
        ended(pidOf(process.@held))
        Connection other = OneProcess.lockedWithin(store.database, Duration.ofSeconds(5))
        closing << other
        def before = sessions()

        when:
        def kept = process.kept()

        then:
        !kept
        process.@held == null
        holders() == [pidOf(other)]
        until("the refused session ended") { sessions() == before }

        cleanup:
        process.destroy()
    }

    /** Handed back to the pool, the session and its lock would have lived on as long as the pool keeps it. */
    def "let go, the lock goes though its connection is handed back to a pool whose session lives on"() {
        given:
        def config = new HikariConfig()
        config.jdbcUrl = "jdbc:postgresql://localhost:${server.port}/${database}?currentSchema=app"
        config.username = "postgres"
        config.password = ""
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

    /** The database going first ends the session, and the lock with it: there is nothing left to let go, or to warn of. */
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

    /** A process starting the moment the lock goes must find everything this one ran written down. */
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
        def heldAsItFinished = new CompletableFuture<List<Integer>>()
        context.getBean(EngineExecutor).executeCode {
            started.complete(true)
            Thread.sleep(300)
            heldAsItFinished.complete(holders())
        }
        started.get(10, TimeUnit.SECONDS)
        def holder = pidOf(context.getBean(OneProcess).@held)

        when:
        context.close()

        then:
        heldAsItFinished.get(1, TimeUnit.SECONDS) == [holder]

        and:
        holders() == []
    }
}
