package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.VERSION
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.run.ModelCallId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class CeilingReachIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final ModelCatalog OTHERS = new ModelCatalog([
            new DeployedModel(new ModelName("small"), [], 100_000, 4.0G, 4_000, [], []),
            new DeployedModel(new ModelName("large"), [], 100_000, 4.0G, 9_000, [], []),
            new DeployedModel(new ModelName("tiny"), [], 100_000, 4.0G, 1_000, [], [])])

    static final ModelCatalog HELD = DeployedModels.HELD

    static final String TOP = "00000008-0000-4000-8000-0000000c0101"

    static final String CHILD = "00000008-0000-4000-8000-0000000c0102"

    static final String GRAND = "00000008-0000-4000-8000-0000000c0103"

    static final String SIBLING = "00000008-0000-4000-8000-0000000c0104"

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    EngineWrites writes

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "ceiling_reach_" + (++databasesMade))
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_versions set ceiling = cast(? as bigint) where entry_version_id = ?::uuid")
                .params(ceiling, VERSION).update()
    }

    // Check the ceiling in its own transaction while holding the run tree lock.
    private boolean checked(ModelCatalog catalog, ModelCallId excluded) {
        store.transactions().execute {
            new CeilingReach(store.session, catalog, writes).stopsAt(tree.lock(groupId(GROUP), runId()).orElseThrow(),
                    runId(), excluded)
        }
    }

    private List<String> stops() {
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts non-string operands to text.
        store.texts("""
                select stop.created_by_kind || ' ' || coalesce(stop.ceiling_run_id::text, '-') || ' ' || stop.created_by
                       || ' ' || case when stop.opened_again_at is null then 'in force' else 'opened' end
                  from run_stops stop order by stop.created_at, stop.run_stop_id
                """)
    }

    private ModelCallId unended() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        new ModelCallId(UUID.fromString(store.texts("select model_call_id::text from model_calls where outcome is null")[0]))
    }

    def "a waiting turnaway does not stop a ceiling lowered to what was already spent, but a resend does"() {
        given:
        RunRows.called(store, RUN, null, 100, null)
        def waiting = unended()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into model_call_turnaways (model_call_id, said, created_by)
                values (?::uuid, 'Demo Sample busy', ?::uuid)
                """).params(waiting.value(), SYSTEM).update()
        ceilingOf(141L)

        expect:
        !checked(HELD, null)
        stops() == []

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update model_call_turnaways set resent_at = greatest(now(), created_at)
                 where model_call_id = ?::uuid
                """).params(waiting.value()).update()

        then:
        checked(HELD, null)
        stops() == ["system ${RUN} ${SYSTEM} in force" as String]
    }

    private void seededTree(Long top, Long child, boolean childKeeps, Long grand, boolean grandKeeps) {
        [[TOP, top, false], [CHILD, child, childKeeps], [GRAND, grand, grandKeeps], [SIBLING, 1L, true]].each {
            String run, Long ceiling, boolean keeps ->
            RunRows.workflow(store, entryOf(run), versionOf(run), GROUP, "Demo Sample " + run[-1], ceiling, false, keeps)
        }
        RunRows.run(store, TOP, GROUP, 11, "Demo Sample", entryOf(TOP), versionOf(TOP), ANN)
        RunRows.beneath(store, CHILD, TOP, GROUP, 12, entryOf(CHILD), versionOf(CHILD), stepOf(CHILD), runStepOf(CHILD))
        RunRows.beneath(store, GRAND, CHILD, GROUP, 13, entryOf(GRAND), versionOf(GRAND), stepOf(GRAND), runStepOf(GRAND))
        RunRows.beneath(store, SIBLING, TOP, GROUP, 14, entryOf(SIBLING), versionOf(SIBLING), stepOf(SIBLING),
                runStepOf(SIBLING), 2)
        RunRows.called(store, TOP, "came_back", 10, 0)
        RunRows.called(store, CHILD, "came_back", 20, 0)
        RunRows.called(store, GRAND, "came_back", 100, 40)
        RunRows.called(store, SIBLING, "came_back", 1000, 0)
    }

    private static String entryOf(String run) {
        "00000006" + run.substring(8)
    }

    private static String versionOf(String run) {
        "00000007" + run.substring(8)
    }

    private static String stepOf(String run) {
        "00000009" + run.substring(8)
    }

    private static String runStepOf(String run) {
        "0000000a" + run.substring(8)
    }

    // Check a call's ceiling in its own transaction while holding the run tree lock.
    private boolean checkedFrom(String calling, ModelCallId excluded = null, ModelCatalog catalog = HELD) {
        store.transactions().execute {
            new CeilingReach(store.session, catalog, writes).stopsAt(tree.lock(groupId(GROUP), runId(TOP)).orElseThrow(),
                    runId(calling), excluded)
        }
    }

    // Check a changed ceiling in its own transaction while holding the run tree lock.
    private boolean checkedOn(String run) {
        store.transactions().execute {
            new CeilingReach(store.session, HELD, writes).stopsAtCeilingOf(
                    tree.lock(groupId(GROUP), runId(TOP)).orElseThrow(), runId(run))
        }
    }

    private static RunId runId(String run) {
        new RunId(UUID.fromString(run))
    }

    private List<String> further() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select further.ceiling_run_id::text
                  from run_stop_additional_ceilings further join runs run on run.run_id = further.ceiling_run_id
                 order by run.depth, run.number
                """)
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

    def "the ceiling a tree is held to is the last change made in force, a raise waiting counting for nothing: #change"() {
        given:
        ceilingOf(1000)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), RUN, 1000, 140, false, ANN, 2)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), RUN, 140, 5000, awaits, ANN, 1)
        if (decided != null) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), enum type run_ceiling_change_outcome.
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(); verbose interval input supplies the hour quantity.
        store.session.sql("""
                insert into run_stops (run_id, root_run_id, created_at, created_by, opened_again_at, opened_again_by)
                values (?::uuid, ?::uuid, now(), ?::uuid, now() + interval '1 hour', ?::uuid)
                """).params(RUN, RUN, ANN, ANN).update()

        when:
        def found = checked(HELD, null)

        then:
        found
        // DB-SPECIFIC: PostgreSQL ::text casts.
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
        new CeilingReach(store.session, HELD, writes).stopsAt(locked, runId(), null)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run tree ${RUN} was used outside the transaction that locked it" as String

        and:
        stops() == []
    }

    def "a call from beneath is judged against the top and each run above it keeping a ceiling, every one reached named, topmost first: #top, #child, #grand"() {
        given:
        seededTree(top, child, true, grand, true)
        def calls = store.digestOf("model_calls")

        when:
        def found = checkedFrom(GRAND)

        then:
        found == (named != null)
        stops() == (named == null ? [] : ["system ${named} ${SYSTEM} in force" as String])
        further() == alsoNamed

        and: "no call touched in either case"
        store.digestOf("model_calls") == calls

        where:
        top   | child | grand || named | alsoNamed
        1170L | 160L  | 140L  || TOP   | [CHILD, GRAND]
        1171L | 160L  | 140L  || CHILD | [GRAND]
        1171L | 161L  | 140L  || GRAND | []
        1170L | 161L  | 141L  || TOP   | []
        null  | 160L  | null  || CHILD | []
        1171L | 161L  | 141L  || null  | []
    }

    def "a run beneath whose version keeps no ceiling of its own is passed over, however low that ceiling: #grand"() {
        given:
        seededTree(null, 1L, false, grand, true)

        when:
        def found = checkedFrom(GRAND)

        then:
        found == reached
        stops() == (reached ? ["system ${GRAND} ${SYSTEM} in force" as String] : [])
        further() == []

        where:
        grand || reached
        140L  || true
        141L  || false
        null  || false
    }

    def "a call out beneath counts toward a ceiling above it at what it sent and the most its model may give back: #grand, left out #excluding"() {
        given:
        seededTree(null, null, true, grand, true)
        RunRows.called(store, GRAND, null, 100, null)

        when:
        def found = checkedFrom(GRAND, excluding ? unended() : null, catalog)

        then:
        found == reached
        stops() == (reached ? ["system ${GRAND} ${SYSTEM} in force" as String] : [])

        where:
        catalog | excluding | grand || reached
        HELD    | false     | 4240L || true
        HELD    | false     | 4241L || false
        OTHERS  | false     | 9240L || true
        OTHERS  | false     | 9241L || false
        HELD    | true      | 140L  || true
        HELD    | true      | 141L  || false
    }

    def "a call turned away beneath counts toward no ceiling above it: #child"() {
        given:
        seededTree(null, child, true, null, true)
        RunRows.called(store, GRAND, "turned_away", 500, null)

        when:
        def found = checkedFrom(GRAND)

        then:
        found == reached
        stops() == (reached ? ["system ${CHILD} ${SYSTEM} in force" as String] : [])

        where:
        child || reached
        160L  || true
        161L  || false
    }

    def "a call from a run outside the tree held is refused, and nothing is written"() {
        given:
        seededTree(1L, null, true, null, true)

        when:
        checkedFrom(RUN)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Tree ${TOP} holds no ceiling of run ${TOP} to judge run ${RUN} by" as String

        and:
        stops() == []
        further() == []
    }

    def "a run's own ceiling alone is judged against what it and every run beneath it spent, whatever is reached above it: #child"() {
        given:
        seededTree(1170L, child, true, 140L, true)

        when:
        def found = checkedOn(CHILD)

        then:
        found == reached
        stops() == (reached ? ["system ${CHILD} ${SYSTEM} in force" as String] : [])
        further() == []

        where:
        child || reached
        160L  || true
        161L  || false
    }

    def "a run keeping no ceiling of its own has none of its own to be judged by, and nothing is written"() {
        given:
        seededTree(1L, 1L, false, null, true)

        when:
        checkedOn(CHILD)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Tree ${TOP} holds no ceiling of run ${CHILD} to judge run ${CHILD} by" as String

        and:
        stops() == []
    }
}
