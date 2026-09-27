package org.lilradish.lite.app.run

import static org.lilradish.lite.domain.run.RunAct.APPROVE_RAISE
import static org.lilradish.lite.domain.run.RunAct.CHANGE_CEILING
import static org.lilradish.lite.domain.run.RunAct.OPEN_AGAIN
import static org.lilradish.lite.domain.run.RunAct.REFUSE_RAISE
import static org.lilradish.lite.domain.run.RunAct.RENAME
import static org.lilradish.lite.domain.run.RunAct.STOP
import static org.lilradish.lite.domain.run.RunAct.WITHDRAW_RAISE
import static org.lilradish.lite.testutil.library.LibraryStore.groupId
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.time.Instant
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.library.ConstantJson
import org.lilradish.lite.app.library.OffersHeld
import org.lilradish.lite.app.pool.PersonRows
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.filling.FilledFields
import org.lilradish.lite.domain.filling.Filling
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.run.CeilingChangeId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.domain.run.RunState
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.library.TakingWorkflow
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory

/** One run as its reader reads it, on a real server running the real baseline. */
class RunsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001101"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001102"

    /** An overseer: reads every run, and may approve a raise. */
    static final String ANN = "00000002-0000-4000-8000-000000001101"

    static final UserId ANN_USER = new UserId("001101")

    /** An operator: reads the runs they started, and approves nothing. */
    static final String CAT = "00000002-0000-4000-8000-000000001103"

    static final UserId CAT_USER = new UserId("001103")

    static final UserId DAN_USER = new UserId("001104")

    static final String WORKFLOW = "00000006-0000-4000-8000-000000001101"

    /** Declares a ceiling of a thousand, and that a raise of it waits on approval. */
    static final String VERSION = "00000007-0000-4000-8000-000000001101"

    static final String SUB_WORKFLOW = "00000006-0000-4000-8000-000000001102"

    static final String SUB_VERSION = "00000007-0000-4000-8000-000000001102"

    /** Cat's. */
    static final String RUN = "00000008-0000-4000-8000-000000001101"

    /** Ann's. */
    static final String ANNS_RUN = "00000008-0000-4000-8000-000000001102"

    static final String BENEATH = "00000008-0000-4000-8000-000000001103"

    static final String OTHER_GROUPS_RUN = "00000008-0000-4000-8000-000000001104"

    static final String RAISE = "0000000b-0000-4000-8000-000000001101"

    /** The step every run of the version asks first. */
    static final String ASKING = "00000009-0000-4000-8000-000000001109"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Runs runs

    /** The key of what the first step gives back. */
    String answer

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "runs_" + (++databasesMade))
        runs = new Runs(store.session, new GroupRoles(store.session), store.transactionManager(),
                new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE))
        store.person(ANN, "001101", "Ann Example")
        store.person(CAT, "001103", "Cat Example")
        store.person("00000002-0000-4000-8000-000000001104", "001104")
        store.group(GROUP, "SUPPORT", "Customer support")
        store.group(OTHER_GROUP, "BILLING", "Billing")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, CAT, "operator")
        store.member(OTHER_GROUP, ANN, "owner")
        RunRows.workflow(store, WORKFLOW, VERSION, GROUP, "Handle a claim", 1000L, true)
        RunRows.workflow(store, SUB_WORKFLOW, SUB_VERSION, GROUP, "Check a claim", 300L, false)
        RunRows.workflow(store, "00000006-0000-4000-8000-000000001103", "00000007-0000-4000-8000-000000001103",
                OTHER_GROUP, "Handle a refund", null, false)
        answer = StepRows.askingSomebody(store, VERSION, GROUP, ASKING)
        StepRows.askingSomebody(store, SUB_VERSION, GROUP)
        StepRows.askingSomebody(store, "00000007-0000-4000-8000-000000001103", OTHER_GROUP)
        RunRows.run(store, RUN, GROUP, 1, "Claim from Ada", WORKFLOW, VERSION, CAT)
        RunRows.run(store, ANNS_RUN, GROUP, 2, "Claim from Grace", WORKFLOW, VERSION, ANN)
        RunRows.run(store, OTHER_GROUPS_RUN, OTHER_GROUP, 1, "Refund for Alan", "00000006-0000-4000-8000-000000001103",
                "00000007-0000-4000-8000-000000001103", ANN)
    }

    private Runs.RunView read(String run, UserId reader) {
        runs.run(groupId(GROUP), new RunId(UUID.fromString(run)), reader)
    }

    private Instant createdAt(String table, String key, String id) {
        store.session.sql("select created_at from ${table} where ${key} = ?::uuid").param(UUID.fromString(id))
                .query(java.time.OffsetDateTime).single().toInstant()
    }

    def "reads a run at the top as its starter reads it: what ran, who started it and when, running, and what it may do"() {
        when:
        def read = read(RUN, CAT_USER)

        then:
        read == new Runs.RunView(
                new RunId(UUID.fromString(RUN)),
                1,
                new RunName("Claim from Ada"),
                null,
                new Runs.Workflow(new EntryId(UUID.fromString(WORKFLOW)), new EntryName("Handle a claim"), 1),
                new PersonRows.Person(new SubjectId(UUID.fromString(CAT)), CAT_USER, new PersonName("Cat Example")),
                createdAt("runs", "run_id", RUN),
                JsonNodeFactory.instance.objectNode(),
                RunState.RUNNING,
                new Runs.At(new WorkflowStepId(UUID.fromString(ASKING)), new StepId("ask")),
                null,
                new RunBudget.Spend(0, 0, false, false),
                new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(1000), true), null),
                [STOP, RENAME, CHANGE_CEILING] as Set)
    }

    def "a run every step of which is done reads as done, on no step, offering no stop but its name and ceiling"() {
        given:
        def executor = EngineExecutors.of(store.database)
        def tree = new RunTree(store.session)
        def writes = new EngineWrites(store.session)
        def none = org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE
        def snapshots = new RunSnapshots(store.session, none)
        def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new org.lilradish.lite.app.codestep.CodeRuns(none), store.transactions(), tree,
                        snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(store.session))
                .drive(groupId(GROUP), new RunId(UUID.fromString(RUN)))
        executor.destroy()
        StepRows.answered(store, ASKING, CAT, "Nothing to add.", [(answer): '"Fine."'])

        when:
        def read = read(RUN, CAT_USER)

        then:
        read.state() == RunState.DONE
        read.at() == null
        read.acts() == [RENAME, CHANGE_CEILING] as Set

        and: "and nothing stopped it"
        read.stop() == null
    }

    def "a run somebody stopped says who and when, and offers opening again in place of stopping"() {
        given:
        RunRows.stopped(store, RUN, ANN)

        when:
        def read = read(RUN, CAT_USER)

        then:
        read.state() == RunState.STOPPED
        read.stop() == new Runs.Stop(createdAt("run_stops", "run_id", RUN), new Runs.Stopper.ByPerson(
                new PersonRows.Person(new SubjectId(UUID.fromString(ANN)), ANN_USER, new PersonName("Ann Example"))))
        read.acts() == [OPEN_AGAIN, RENAME, CHANGE_CEILING] as Set
    }

    /** A ceiling reached names the run it belonged to, which may be one beneath, and never a person. */
    def "a run its ceiling stopped says whose ceiling it was, and names nobody"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")
        RunRows.stoppedByCeiling(store, RUN, reached)

        when:
        def read = read(RUN, ANN_USER)

        then:
        read.state() == RunState.STOPPED
        read.stop().by() == new Runs.Stopper.ByCeiling(new Runs.NumberedRun(new RunId(UUID.fromString(reached)), number))

        where:
        reached || number
        RUN     || 1
        BENEATH || 3
    }

    /** Stopped and opened with the run at the top, and held to its ceiling where its version keeps none of its own. */
    def "a run beneath another names the run above, nobody as its starter, reads the stop and ceiling above, and offers nothing"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")
        RunRows.stopped(store, RUN, ANN)

        when:
        def read = read(BENEATH, reader)

        then:
        read.number() == 3
        read.name() == null
        read.above() == new RunId(UUID.fromString(RUN))
        read.workflow() == new Runs.Workflow(new EntryId(UUID.fromString(SUB_WORKFLOW)), new EntryName("Check a claim"), 1)
        read.startedBy() == null
        read.state() == RunState.STOPPED
        read.stop().by() instanceof Runs.Stopper.ByPerson
        read.ceiling() == new Runs.CeilingHeld.AtTop(new Runs.NumberedRun(new RunId(UUID.fromString(RUN)), 1))
        read.acts().isEmpty()

        where:
        reader << [ANN_USER, CAT_USER]
    }

    def "a run beneath another whose version keeps a ceiling of its own reads that one"() {
        given:
        RunRows.workflow(store, "00000006-0000-4000-8000-000000001104", "00000007-0000-4000-8000-000000001104", GROUP,
                "Keep a claim", 300L, false, true)
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, "00000006-0000-4000-8000-000000001104",
                "00000007-0000-4000-8000-000000001104", "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")

        expect:
        read(BENEATH, ANN_USER).ceiling() == new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(300), false), null)
    }

    private void taken(String version, Map declared) {
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: "workflow", side: "takes"]
                + declared)
    }

    /** Kept as a start keeps it, in the store's own order, and read out as that start's request sent it. */
    def "a run reads what it was started with as its start request sent it, each value as its text, in declared order"() {
        given:
        def workflow = "00000006-0000-4000-8000-000000001105"
        def version = "00000007-0000-4000-8000-000000001105"
        def list = "00000007-0000-4000-8000-000000001106"
        def run = "00000008-0000-4000-8000-000000001105"
        def sender = "0000000c-0000-4000-8000-000000001105"
        RunRows.workflow(store, workflow, version, GROUP, "Take a claim", null, false)
        StepRows.askingSomebody(store, version, GROUP)
        TakingWorkflow.list(store, "00000006-0000-4000-8000-000000001106", list, GROUP)
        taken(version, [position: 1, name: "claim"])
        taken(version, [position: 2, name: "amount", kind: "number"])
        taken(version, [position: 3, name: "urgent", kind: "yes_no"])
        taken(version, [position: 4, name: "noticed", kind: "moment"])
        taken(version, [position: 5, name: "tags", most: 5])
        taken(version, [id: sender, position: 6, name: "sender", kind: "fields"])
        taken(version, [parent: sender, position: 1, name: "name"])
        taken(version, [parent: sender, position: 2, name: "since", kind: "moment"])
        TakingWorkflow.field(store, version, 7, "kind", "term", [list: list])
        def request = '''
                {"kind": "damaged", "sender": {"since": "2026-09-24T10:00:00+02:00", "name": "Ada"},
                 "tags": ["late", "torn"], "noticed": null, "urgent": "true", "amount": "12.50", "claim": "Lost bag"}'''
        def takes = store.transactions().execute {
            OffersHeld.held(store.session, new GroupRoles(store.session).stillReached(CAT_USER, groupId(GROUP),
                    GroupPermission.START_RUN), versionId(version)).orElseThrow().takes()
        }
        def filled = Filling.of(takes, ConstantJson.sent(request)) as FilledFields
        store.session.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                                  started_with, created_by)
                values (?::uuid, ?::uuid, 4, 'Claim from Alan', ?::uuid, ?::uuid, ?::uuid, 0, ?::jsonb, ?::uuid)
                """).params(run, GROUP, workflow, version, run, CanonicalJson.write(filled.values()), CAT).update()

        when:
        def read = read(run, CAT_USER)

        then:
        read.startedWith() == JsonMapper.builder().build().readTree(request)
        read.startedWith().get("amount") == JsonNodeFactory.instance.stringNode("12.50")
        read.startedWith().propertyNames().toList() ==
                ["claim", "amount", "urgent", "noticed", "tags", "sender", "kind"]
        read.startedWith().get("sender").propertyNames().toList() == ["name", "since"]
    }

    def "a run at the top taking nothing reads as started with nothing"() {
        expect:
        read(RUN, ANN_USER).startedWith() == JsonNodeFactory.instance.objectNode()
    }

    /** The store keeps nothing a run beneath was started with. */
    def "a run beneath another reads as started with none"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")

        expect:
        read(BENEATH, ANN_USER).startedWith() == null
    }

    /**
     * A call turned away every time counts for nothing; one still out counts what was sent, and says that what
     * comes back is not known yet. A run counts every run beneath it; one beneath counts only its own.
     */
    def "what a run has spent is every call but those turned away, over it and every run beneath it"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")
        RunRows.called(store, RUN, "came_back", 100, 20)
        RunRows.called(store, RUN, "errored", 50, null)
        RunRows.called(store, RUN, "turned_away", 999, null)
        RunRows.called(store, RUN, null, 30, null)
        RunRows.called(store, BENEATH, "came_back", 10, 5)
        RunRows.called(store, ANNS_RUN, "came_back", 7000, 7000)

        expect:
        read(RUN, ANN_USER).spend() == new RunBudget.Spend(190, 25, true, true)
        read(RUN, ANN_USER).spend().spent() == 215
        read(BENEATH, ANN_USER).spend() == new RunBudget.Spend(10, 5, false, true)
    }

    /** Waiting, it is beside the ceiling in force and not in it; whoever asked may withdraw it and nobody else. */
    def "a raise waiting is read beside the ceiling in force, offered for deciding to all but whoever asked for it"() {
        given:
        RunRows.ceilingChanged(store, "0000000b-0000-4000-8000-000000001102", RUN, 1000L, 600L, false, ANN, 20)
        RunRows.ceilingChanged(store, RAISE, RUN, 600L, null, true, CAT, 10)

        when:
        def read = read(RUN, reader)

        then:
        read.ceiling() == new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(600), true),
                new CeilingRaises.Waiting(new CeilingChangeId(UUID.fromString(RAISE)), null,
                        new PersonRows.Person(new SubjectId(UUID.fromString(CAT)), CAT_USER, new PersonName("Cat Example")),
                        createdAt("run_ceiling_changes", "run_ceiling_change_id", RAISE), reader == CAT_USER))
        read.acts() == acts as Set

        where:
        reader   || acts
        CAT_USER || [STOP, RENAME, CHANGE_CEILING, WITHDRAW_RAISE]
        ANN_USER || [STOP, RENAME, CHANGE_CEILING, APPROVE_RAISE, REFUSE_RAISE]
    }

    /** Stopped, a run still offers deciding a raise of its ceiling, as it offers its name and ceiling. */
    def "a raise waiting on a stopped run is offered for deciding all the same"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 10)
        RunRows.stopped(store, RUN, CAT)

        expect:
        read(RUN, ANN_USER).acts() == [OPEN_AGAIN, RENAME, CHANGE_CEILING, APPROVE_RAISE, REFUSE_RAISE] as Set
    }

    /** A raise with nothing left of it waiting is in no one's way: refused, withdrawn or approved, it is gone from the page. */
    def "a raise decided is no longer waiting, and only an approved one is in force"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 5000L, true, CAT, 10)
        store.session.sql("""
                update run_ceiling_changes set outcome = cast(? as run_ceiling_change_outcome), decided_at = now(),
                       decided_by = ?::uuid
                """).params(outcome, outcome == "withdrawn" ? CAT : ANN).update()

        when:
        def read = read(RUN, ANN_USER)

        then:
        read.ceiling() == new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(inForce), true), null)
        read.acts() == [STOP, RENAME, CHANGE_CEILING] as Set

        where:
        outcome     || inForce
        "approved"  || 5000
        "refused"   || 1000
        "withdrawn" || 1000
    }

    def "a run with no ceiling reads as one that may spend without limit"() {
        when:
        def read = runs.run(groupId(OTHER_GROUP), new RunId(UUID.fromString(OTHER_GROUPS_RUN)), ANN_USER)

        then:
        read.ceiling() == new Runs.CeilingHeld.Own(new RunBudget.InForce(null, false), null)
    }

    /**
     * Another group's run, one nobody holds, and one somebody else started read by one who may read only their
     * own, are one refusal: telling them apart would say what runs somebody else started.
     */
    def "a run the reader may not read in the group is refused as no run"() {
        when:
        read(named, CAT_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
        refused.message == "That run is not in view."

        where:
        named << [ANNS_RUN, OTHER_GROUPS_RUN, "00000008-0000-4000-8000-000000001109"]
    }

    def "somebody holding nothing in the group is refused as no group, whatever run they name"() {
        when:
        read(named, DAN_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.GROUP_NOT_IN_VIEW

        where:
        named << [RUN, "00000008-0000-4000-8000-000000001109"]
    }

    /** Written round the type, by hand or by a restore: the read fails rather than showing it or leaving it out. */
    def "a stored name the run's type refuses fails the read, naming the run"() {
        given:
        store.session.sql("update runs set name = ' ' where run_id = ?::uuid").params(RUN).update()

        when:
        read(RUN, ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Run ${RUN} holds a name this system will not show"
    }

    /** A name kept twice is no row: jsonb keeps the last of each name, so the store cannot hold one. */
    def "what a run was started with stored in no shape its version takes fails the read, naming the run: #stored"() {
        given:
        taken(VERSION, [position: 1, name: "amount", kind: "number"])
        taken(VERSION, [position: 2, name: "tags", most: 5])
        store.session.sql("update runs set started_with = ?::jsonb where run_id = ?::uuid").params(stored, RUN).update()

        when:
        read(RUN, ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Run ${RUN} was started with other than what its version takes"
        failed.cause instanceof IllegalStateException
        failed.cause.message == "Filling was handed values kept in no shape of the fields they fill"

        where:
        stored << ['{}', '{"amount": 12, "other": null}', '{"amount": "twelve", "tags": []}',
                   '{"amount": 12, "tags": null}']
    }
}
