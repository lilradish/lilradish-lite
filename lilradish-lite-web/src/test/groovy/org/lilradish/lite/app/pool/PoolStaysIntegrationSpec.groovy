package org.lilradish.lite.app.pool

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A current stay held from outside this package, on a real server running the real baseline: whether
 * somebody is in the pool, and that holding their stay keeps a removal waiting and nobody else.
 */
class PoolStaysIntegrationSpec extends Specification {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"

    static final String FIRST_STEWARD = "00000001-0000-4000-8000-000000000001"

    static final String PERSON = "00000002-0000-4000-8000-000000000901"

    static final String REMOVED = "00000002-0000-4000-8000-000000000902"

    static final String NEVER_POOLED = "00000002-0000-4000-8000-000000000903"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    DataSource database

    @AutoCleanup("shutdownNow")
    ExecutorService racing = Executors.newSingleThreadExecutor()

    def setupSpec() {
        database = Baseline.appliedTo(server, "postgres")
        def session = JdbcClient.create(database)
        [[PERSON, "000901"], [REMOVED, "000902"], [NEVER_POOLED, "000903"]].each {
            session.sql("""
                    insert into subjects (subject_id, kind, user_id, display_name, created_by)
                    values (?::uuid, 'person', ?, 'Somebody', ?::uuid)
                    """).params(it[0], it[1], SEEDER).update()
        }
        session.sql("insert into pool_members (subject_id, created_by) values (?::uuid, ?::uuid)")
                .params(PERSON, FIRST_STEWARD).update()
        session.sql("""
                insert into pool_members (subject_id, created_by, removed_at, removed_by)
                values (?::uuid, ?::uuid, now(), ?::uuid)
                """).params(REMOVED, FIRST_STEWARD, FIRST_STEWARD).update()
    }

    def "somebody is held as in the pool exactly while their stay has not ended"() {
        given:
        def (Connection connection, PoolStays stays) = heldOn()

        expect:
        stays.holdCurrentStay(new SubjectId(UUID.fromString(person))) == pooled

        cleanup:
        connection.rollback()
        connection.close()

        where:
        person                                               || pooled
        PERSON                                               || true
        REMOVED                                              || false
        NEVER_POOLED                                         || false
        SystemPrincipal.WORKFLOW_RUNNER.subject().value().toString() || false
    }

    /** Shared by two holders at once, and exclusive to neither: only what ends a stay waits on it. */
    def "a held stay keeps what would end it waiting, and another holder waiting on nothing"() {
        given:
        def (Connection holder, PoolStays stays) = heldOn()
        stays.holdCurrentStay(new SubjectId(UUID.fromString(PERSON)))
        def (Connection another, PoolStays anotherStays) = heldOn()

        when:
        def sharedToo = anotherStays.holdCurrentStay(new SubjectId(UUID.fromString(PERSON)))
        def ending = racing.submit({
            def remover = database.connection
            try {
                remover.createStatement().withCloseable {
                    it.execute("select 1 from pool_members where subject_id = '${PERSON}' and removed_at is null for update")
                }
            } finally {
                remover.close()
            }
        } as Callable<Object>)
        untilWaiting()
        another.rollback()
        def waitingAfterOne = !ending.isDone()
        holder.rollback()
        ending.get(10, TimeUnit.SECONDS)

        then:
        sharedToo
        waitingAfterOne

        cleanup:
        holder.close()
        another.close()
    }

    /** A transaction of the feature's own, and the stays read inside it. */
    private List heldOn() {
        def connection = database.connection
        connection.autoCommit = false
        [connection, new PoolStays(JdbcClient.create(new SingleConnectionDataSource(connection, true)))]
    }

    private void untilWaiting() {
        def deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (JdbcClient.create(database).sql("""
                select count(*) from pg_stat_activity
                 where datname = current_database() and wait_event_type = 'Lock'
                """).query(Long).single() < 1) {
            assert System.nanoTime() < deadline: "nothing ever waited on the held stay"
            Thread.sleep(10)
        }
    }
}
