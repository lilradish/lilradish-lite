package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TreeWorkflows.CAT
import static org.lilradish.lite.app.run.fixture.TreeWorkflows.GROUP
import static org.lilradish.lite.app.run.fixture.TreeWorkflows.ROOT
import static org.lilradish.lite.testutil.library.LibraryStore.attempting
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.lilradish.lite.app.run.fixture.TreeWorkflows
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.Blocking
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunNumberingRaceIntegrationSpec extends Specification {

    static final String TAKEN_FIRST = "00000008-0000-4000-8000-000000003702"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    EngineWiring wiring

    ExecutorService racing = Executors.newSingleThreadExecutor()

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_numbering_race_" + (++databasesMade))
        TreeWorkflows.seed(store)
        wiring = new EngineWiring(store, new ScriptedModelCalls(), CodeStepsHeld.NONE)
    }

    def cleanup() {
        racing.shutdownNow()
        wiring.executor.destroy()
    }

    def "a run beneath started while a start in its group holds the numbering waits for it, and takes the number after"() {
        given:
        TreeWorkflows.run(store, TreeWorkflows.ESCALATE, TreeWorkflows.ESCALATE_VERSION,
                '{"ticket":"The printer is on fire."}')
        def other = store.holding()
        RunNumbering.hold(JdbcClient.create(new SingleConnectionDataSource(other, true)), groupId(GROUP))
        other.createStatement().withCloseable {
            it.execute("""
                    insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                                      started_with, created_by)
                    values ('${TAKEN_FIRST}', '${GROUP}', 2, 'Taken first', '${TreeWorkflows.ESCALATE}',
                            '${TreeWorkflows.ESCALATE_VERSION}', '${TAKEN_FIRST}', 0, '{"ticket":"Another."}',
                            '${CAT}')""")
        }

        when:
        def driven = attempting(racing) { wiring.engine.drive(groupId(GROUP), new RunId(UUID.fromString(ROOT))) }
        Blocking.untilBlockedBy(store, other)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        def whileWaiting = store.count("select count(*) from runs where parent_run_id = ?::uuid", ROOT)
        other.commit()
        other.close()
        def outcome = driven.get(10, TimeUnit.SECONDS)

        then:
        !(outcome instanceof Throwable)
        whileWaiting == 0
        store.texts("select number || ' ' || coalesce(name, '-') || ' ' || depth from runs order by number") ==
                ["1 A run 0", "2 Taken first 0", "3 - 1"]

        and:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where parent_run_id = ?::uuid", ROOT) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from runs where parent_run_id = ?::uuid", TAKEN_FIRST) == 0
    }
}
