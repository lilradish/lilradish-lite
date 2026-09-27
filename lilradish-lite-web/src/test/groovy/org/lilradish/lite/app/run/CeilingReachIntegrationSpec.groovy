package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Whether a run tree has reached its ceiling, on a real server running the real baseline: every call of the tree
 * counted at what it sent and what came back, one still out at what it sent and the most its model may give
 * back, one turned away at nothing; and the tree stopped by the system exactly where the ceiling is reached.
 */
class CeilingReachIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    /** Holds no model named general, which every call here is to; the largest it may give back is neither end. */
    static final ModelCatalog OTHERS = new ModelCatalog([
            new DeployedModel(new ModelName("small"), [], 100_000, 4.0G, 4_000, []),
            new DeployedModel(new ModelName("large"), [], 100_000, 4.0G, 9_000, []),
            new DeployedModel(new ModelName("tiny"), [], 100_000, 4.0G, 1_000, [])])

    static final ModelCatalog HELD = DeployedModels.HELD

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    EngineWrites writes

    def setupSpec() {
        LibraryStore.template(server)
    }

    /** Every feature's tree holds one call come back, 100 sent and 40 back: 140 spent before anything else. */
    def setup() {
        store = LibraryStore.copied(server, "ceiling_reach_" + (++databasesMade))
        tree = new RunTree(store.session)
        writes = new EngineWrites(store.session)
        TicketWorkflow.seed(store)
        TicketWorkflow.run(store)
        RunRows.called(store, RUN, "came_back", 100, 40)
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private void ceilingOf(Long ceiling) {
        store.session.sql("update workflow_versions set ceiling = cast(? as bigint) where entry_version_id = ?::uuid")
                .params(ceiling, VERSION).update()
    }

    /** Checked in a transaction of its own, the tree locked as every caller locks it. */
    private boolean checked(ModelCatalog catalog, ModelCallId excluded) {
        store.transactions().execute {
            new CeilingReach(store.session, catalog, writes).stopsAt(tree.lock(groupId(GROUP), runId()).orElseThrow(),
                    excluded)
        }
    }

    /** Each stop of the run, as who made it, whose ceiling it names, who is its author, and whether it holds. */
    private List<String> stops() {
        store.texts("""
                select stop.created_by_kind || ' ' || coalesce(stop.ceiling_run_id::text, '-') || ' ' || stop.created_by
                       || ' ' || case when stop.opened_again_at is null then 'in force' else 'opened' end
                  from run_stops stop order by stop.created_at, stop.run_stop_id
                """)
    }

    private ModelCallId unended() {
        new ModelCallId(UUID.fromString(store.texts("select model_call_id::text from model_calls where outcome is null")[0]))
    }

    def "a tree reaches its ceiling exactly where what it spent comes to it: #call against #ceiling"() {
        given:
        RunRows.called(store, RUN, outcome, 100, outcome == "came_back" ? 40L : null)
        if (ceiling != null) {
            ceilingOf(ceiling)
        }
        def calls = store.digestOf("model_calls")

        when:
        def found = checked(catalog, excluding ? unended() : null)

        then:
        found == reached
        stops() == (reached ? ["system ${RUN} ${SYSTEM} in force" as String] : [])

        and: "no call touched in either case"
        store.digestOf("model_calls") == calls

        where:
        call                              | outcome             | catalog | excluding | ceiling || reached
        "come back"                       | "came_back"         | HELD    | false     | null    || false
        "come back"                       | "came_back"         | HELD    | false     | 279     || true
        "come back"                       | "came_back"         | HELD    | false     | 280     || true
        "come back"                       | "came_back"         | HELD    | false     | 281     || false
        "gone wrong"                      | "errored"           | HELD    | false     | 239     || true
        "gone wrong"                      | "errored"           | HELD    | false     | 240     || true
        "gone wrong"                      | "errored"           | HELD    | false     | 241     || false
        "nothing came back for"           | "nothing_came_back" | HELD    | false     | 239     || true
        "nothing came back for"           | "nothing_came_back" | HELD    | false     | 240     || true
        "nothing came back for"           | "nothing_came_back" | HELD    | false     | 241     || false
        "out, to a model held"            | null                | HELD    | false     | null    || false
        "out, to a model held"            | null                | HELD    | false     | 4239    || true
        "out, to a model held"            | null                | HELD    | false     | 4240    || true
        "out, to a model held"            | null                | HELD    | false     | 4241    || false
        "out, to a model no longer held"  | null                | OTHERS  | false     | 9239    || true
        "out, to a model no longer held"  | null                | OTHERS  | false     | 9240    || true
        "out, to a model no longer held"  | null                | OTHERS  | false     | 9241    || false
        "turned away"                     | "turned_away"       | HELD    | false     | 139     || true
        "turned away"                     | "turned_away"       | HELD    | false     | 140     || true
        "turned away"                     | "turned_away"       | HELD    | false     | 141     || false
        "out, and the one left out"       | null                | HELD    | true      | 139     || true
        "out, and the one left out"       | null                | HELD    | true      | 140     || true
        "out, and the one left out"       | null                | HELD    | true      | 141     || false
    }

    /** The version says 1000; the tree has spent 140, so only a change down to 140 holding reaches it. */
    def "the ceiling a tree is held to is the last change made in force, a raise waiting counting for nothing: #change"() {
        given:
        ceilingOf(1000)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), RUN, 1000, 140, false, ANN, 2)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), RUN, 140, 5000, awaits, ANN, 1)
        if (decided != null) {
            store.session.sql("""
                    update run_ceiling_changes set outcome = cast(? as run_ceiling_change_outcome), decided_at = now(),
                                                   decided_by = ?::uuid
                     where position = 2""").params(decided, TicketWorkflow.BEN).update()
        }

        when:
        def found = checked(HELD, null)

        then:
        found == reached
        stops() == (reached ? ["system ${RUN} ${SYSTEM} in force" as String] : [])

        where:
        change                         | awaits | decided    || reached
        "a raise waiting on approval"  | true   | null       || true
        "a raise refused"              | true   | "refused"  || true
        "a raise approved"             | true   | "approved" || false
        "a raise holding at once"      | false  | null       || false
    }

    def "a stop made for the ceiling is dated no earlier than the run's last opening again"() {
        given:
        ceilingOf(140)
        store.session.sql("""
                insert into run_stops (run_id, root_run_id, created_at, created_by, opened_again_at, opened_again_by)
                values (?::uuid, ?::uuid, now(), ?::uuid, now() + interval '1 hour', ?::uuid)
                """).params(RUN, RUN, ANN, ANN).update()

        when:
        def found = checked(HELD, null)

        then:
        found
        store.texts("""
                select (made.created_at >= earlier.opened_again_at)::text
                  from run_stops made join run_stops earlier on earlier.opened_again_at is not null
                 where made.opened_again_at is null""") == ["true"]

        and: "the earlier stop left as it was"
        stops() == ["person - ${ANN} opened" as String, "system ${RUN} ${SYSTEM} in force" as String]
    }

    def "a tree held no longer is refused before anything is read or written"() {
        given:
        ceilingOf(140)
        def locked = store.transactions().execute { tree.lock(groupId(GROUP), runId()).orElseThrow() }

        when:
        new CeilingReach(store.session, HELD, writes).stopsAt(locked, null)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run tree ${RUN} was used outside the transaction that locked it" as String

        and:
        stops() == []
    }
}
