package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TreeWorkflows.CAT
import static org.lilradish.lite.app.run.fixture.TreeWorkflows.GROUP
import static org.lilradish.lite.app.run.fixture.TreeWorkflows.ROOT
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.run.fixture.TreeWorkflows
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.StepAct
import org.lilradish.lite.domain.run.StepFailure
import org.lilradish.lite.domain.run.StepGround
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RouteRunIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    List<EngineWiring> wirings = []

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "route_run_" + (++databasesMade))
    }

    def cleanup() {
        wirings.each { it.executor.destroy() }
    }

    private EngineWiring wired(int maximumDepth = 32) {
        def wiring = TreeWorkflows.wired(store, maximumDepth)
        wirings << wiring
        wiring
    }

    private void routed(EngineWiring wiring, String category) {
        store.transactions().executeWithoutResult {
            TreeWorkflows.run(store, TreeWorkflows.ROUTED, TreeWorkflows.ROUTED_VERSION,
                    category == null ? '{"category":null}' : "{\"category\":\"${category}\"}")
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), new RunId(UUID.fromString(ROOT))).orElseThrow())
        }
    }

    private List<String> branches() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("""
                select run.entry_version_id || ' ' || run.parent_route_case_id || ' ' || run.depth || ' '
                       || (select count(*) from production_inputs taken where taken.branch_run_id = run.run_id)
                  from runs run where run.parent_run_id = ?::uuid
                """, ROOT)
    }

    private List<String> failures() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("""
                select reason || ' ' || coalesce(detail, '-') || ' ' || (production_id is null)
                  from run_step_failures where run_id = ?::uuid
                """, ROOT)
    }

    private List<String> holds() {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select reason::text from run_step_holds where run_id = ?::uuid and released_at is null", ROOT)
    }

    private StepPosition route(EngineWiring wiring) {
        def read = store.transactions().execute {
            wiring.snapshots.asRead(groupId(GROUP), new RunId(UUID.fromString(ROOT)))
        }
        StepPositions.of(read, read.steps()[0])
    }

    def "a route runs the case its value claims, or its fallback, at the version that case pins"() {
        given:
        TreeWorkflows.seed(store)
        def wiring = wired()

        when:
        routed(wiring, category)

        then:
        branches() == ["${version} ${routeCase} 1 1" as String]
        route(wiring) instanceof StepPosition.Beneath

        and:
        failures() == []
        holds() == []

        where:
        category || version                              | routeCase
        "go"     || TreeWorkflows.GOING_VERSION          | TreeWorkflows.GO_CASE
        "wait"   || TreeWorkflows.FALLING_BACK_VERSION   | TreeWorkflows.FALLBACK_CASE
    }

    def "a route with no fallback fails on a value no case claims, naming that value or nothing, and offers nothing"() {
        given:
        TreeWorkflows.seed(store, false)
        def wiring = wired()

        when:
        routed(wiring, category)

        then:
        failures() == [written]
        def position = route(wiring)
        position == new StepPosition.Failed(new StepFailure.Recorded(RunStepFailureReason.UNCLAIMED_VALUE, null),
                position.since())
        def read = store.transactions().execute {
            wiring.snapshots.asRead(groupId(GROUP), new RunId(UUID.fromString(ROOT)))
        }
        StepAct.values().findAll { it.refusal(StepGround.of(read, read.steps()[0], position, null)) == null } == []
        read.steps()[0].failures()*.term() == [category]

        and:
        branches() == []
        holds() == []
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where run_id = ?::uuid", ROOT) == 0

        where:
        category || written
        "wait"   || "unclaimed_value wait true"
        null     || "unclaimed_value - true"
    }

    def "a route's value no case claims fails before its depth, its depth before its stop, and only then is it held"() {
        given:
        TreeWorkflows.seed(store, false)
        store.stopped(TreeWorkflows.GOING, CAT)
        def wiring = wired(most)

        when:
        routed(wiring, category)

        then:
        failures() == failed
        holds() == held

        and:
        branches() == []

        where:
        category | most || failed                          | held
        "wait"   | 1    || ["unclaimed_value wait true"]   | []
        "go"     | 1    || ["too_deep - true"]             | []
        "go"     | 2    || []                              | ["entry_stopped"]
    }

    def "a route whose value claims a case fails as too deep where its branch would go deeper than runs may"() {
        given:
        TreeWorkflows.seed(store)
        def wiring = wired(1)

        when:
        routed(wiring, "go")

        then:
        failures() == ["too_deep - true"]

        and:
        branches() == []
        holds() == []
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where root_run_id = ?::uuid", ROOT) == 0
    }
}
