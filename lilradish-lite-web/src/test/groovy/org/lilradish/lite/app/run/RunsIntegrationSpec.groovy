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
import java.sql.ResultSet
import java.time.Instant
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.library.ConstantJson
import org.lilradish.lite.app.library.OffersHeld
import org.lilradish.lite.app.pool.PersonRows
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.filling.FilledFields
import org.lilradish.lite.domain.filling.Filling
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.run.CeilingChangeId
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunName
import org.lilradish.lite.domain.run.RunState
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.library.TakingWorkflow
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory

class RunsIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000001101"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000001102"

    static final String ANN = "00000002-0000-4000-8000-000000001101"

    static final UserId ANN_USER = new UserId("001101")

    static final String CAT = "00000002-0000-4000-8000-000000001103"

    static final UserId CAT_USER = new UserId("001103")

    static final UserId DAN_USER = new UserId("001104")

    static final String WORKFLOW = "00000006-0000-4000-8000-000000001101"

    static final String VERSION = "00000007-0000-4000-8000-000000001101"

    static final String SUB_WORKFLOW = "00000006-0000-4000-8000-000000001102"

    static final String SUB_VERSION = "00000007-0000-4000-8000-000000001102"

    static final String RUN = "00000008-0000-4000-8000-000000001101"

    static final String ANNS_RUN = "00000008-0000-4000-8000-000000001102"

    static final String BENEATH = "00000008-0000-4000-8000-000000001103"

    static final String OTHER_GROUPS_RUN = "00000008-0000-4000-8000-000000001104"

    static final String RAISE = "0000000b-0000-4000-8000-000000001101"

    static final String ASKING = "00000009-0000-4000-8000-000000001109"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    Runs runs

    String answer

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "runs_" + (++databasesMade))
        runs = new Runs(store.session, new GroupRoles(store.session), store.transactionManager(),
                new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([])))
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
        RunRows.run(store, RUN, GROUP, 1, "Claim from Ari", WORKFLOW, VERSION, CAT)
        RunRows.run(store, ANNS_RUN, GROUP, 2, "Claim from Greno", WORKFLOW, VERSION, ANN)
        RunRows.run(store, OTHER_GROUPS_RUN, OTHER_GROUP, 1, "Refund for Arlo", "00000006-0000-4000-8000-000000001103",
                "00000007-0000-4000-8000-000000001103", ANN)
    }

    private Runs.RunView read(String run, UserId reader) {
        runs.run(groupId(GROUP), new RunId(UUID.fromString(run)), reader)
    }

    private Instant createdAt(String table, String key, String id) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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
                new RunName("Claim from Ari"),
                null,
                null,
                [],
                new Runs.Workflow(new EntryId(UUID.fromString(WORKFLOW)), new EntryName("Handle a claim"), 1),
                new PersonRows.Person(new SubjectId(UUID.fromString(CAT)), CAT_USER, new PersonName("Cat Example")),
                createdAt("runs", "run_id", RUN),
                JsonNodeFactory.instance.objectNode(),
                null,
                RunState.RUNNING,
                new Runs.At(new WorkflowStepId(UUID.fromString(ASKING)), new StepId("ask")),
                null,
                new RunBudget.Spend(0, 0, false, 0, 0),
                new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(1000), true), null),
                [STOP, RENAME, CHANGE_CEILING] as Set, [])
    }

    def "a run every step of which is done reads as done, on no step, offering no stop but its name and ceiling"() {
        given:
        def executor = EngineExecutors.of(store.database)
        def tree = new RunTree(store.session)
        def writes = new EngineWrites(store.session)
        def none = org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE
        def snapshots = new RunSnapshots(store.session, none, new SearchWays([]))
        def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new org.lilradish.lite.app.codestep.CodeRuns(none), store.transactions(), tree,
                        snapshots, writes, executor, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles)
                .drive(groupId(GROUP), new RunId(UUID.fromString(RUN)))
        executor.destroy()
        StepRows.answered(store, ASKING, CAT, "Nothing to add.", [(answer): '"Fine."'])

        when:
        def read = read(RUN, CAT_USER)

        then:
        read.state() == RunState.DONE
        read.at() == null
        read.acts() == [RENAME, CHANGE_CEILING] as Set
        read.conversationEvents()*.kind() == ["step_reached", "answered_by_hand"]
        read.conversationEvents()*.id().toSet().size() == 2
        with(read.conversationEvents()[1]) {
            stepId() == UUID.fromString(ASKING)
            number() == 1
            by() == new org.lilradish.lite.app.pool.PersonAnswer(CAT_USER.value(), "Cat Example")
            values() == [new RunActs.Value("answer", JsonNodeFactory.instance.stringNode("Fine."), null)]
            why() == "Nothing to add."
            decisions() == null
        }

        and: "and nothing stopped it"
        read.stop() == null
    }

    def "a hand answer survives an absent code list and a changed file shape without changing its saved rows"() {
        given:
        CodeWorkflow.seed(store, 1, false, "person")
        CodeWorkflow.run(store)
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def held = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, producer,
                                       reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, entry_version_id, workflow_step_id, kind, producer,
                       reviewed_by_model, tries, ?::uuid, 'system'
                  from workflow_steps where workflow_step_id = ?::uuid
                returning run_step_id::text
                """).params(CodeWorkflow.RUN, RunRows.WORKFLOW_RUNNER, CodeWorkflow.SEND).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, may_run_again, try_number, producer, created_by,
                                         created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'person', false, 1, false, 1, 'person',
                        ?::uuid, 'system', now(), ?::uuid, 'person', 'Demo Sample earlier answer')
                returning production_id::text
                """).params(held, CodeWorkflow.RUN, CodeWorkflow.RUN, RunRows.WORKFLOW_RUNNER,
                CodeWorkflow.CAT).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, field_name,
                                               field_standing, value)
                values (?::uuid, 'code_step', 'person', 'receipt', 'always', '"Demo Sample old"'::jsonb)
                """).params(production).update()
        def shape = switch (now) {
            case "missing_list" -> new FieldShape.Term(new EntryVersionId(UUID.randomUUID()))
            case "file" -> new FieldShape.File([new MediaTypeName("text/plain")], 100L)
            default -> new FieldShape.Text(64)
        }
        def release = CodeStepsHeld.of(new SpecCodeStep(CodeWorkflow.CODE_STEP, [CodeWorkflow.REPLY],
                [SpecCodeStep.standing("receipt", shape)], false))
        def snapshot = new RunSnapshots(store.session, release, new SearchWays([]))
                .asRead(groupId(CodeWorkflow.GROUP), new RunId(UUID.fromString(CodeWorkflow.RUN)))
        def numbered = new RunActs.NumberedRun(UUID.fromString(CodeWorkflow.RUN), 1)
        def before = ["run_steps", "productions", "production_values"].collect { table ->
            store.count("select count(*) from ${table}" as String)
        }

        when:
        def events = RunActs.read(store.session, numbered, numbered, UUID.fromString(CodeWorkflow.GROUP), snapshot)

        then:
        def hand = events.find { it.kind() == "answered_by_hand" }
        hand.values() == [new RunActs.Value("receipt", JsonNodeFactory.instance.stringNode(expected), earlier)]
        ["run_steps", "productions", "production_values"].collect { table ->
            store.count("select count(*) from ${table}" as String)
        } == before

        where:
        now            || expected            | earlier
        "missing_list" || '"Demo Sample old"' | true
        "file"         || '"Demo Sample old"' | true
        "text"         || "Demo Sample old"   | null
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

    def "a run its ceiling stopped says whose ceiling it was, and names nobody"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")
        RunRows.stoppedByCeiling(store, RUN, reached)

        when:
        def read = read(RUN, ANN_USER)

        then:
        read.state() == RunState.STOPPED
        read.stop().by() ==
                new Runs.Stopper.ByCeiling([new Runs.NumberedRun(new RunId(UUID.fromString(reached)), number)])
        def systemStop = read.conversationEvents().findAll { it.kind() == "stopped_by_ceiling" }
        systemStop.size() == 1
        systemStop[0].by() == null
        systemStop[0].ceilings() == [new RunActs.NumberedRun(UUID.fromString(reached), number)]
        systemStop[0].run() == new RunActs.NumberedRun(UUID.fromString(RUN), 1)

        where:
        reached || number
        RUN     || 1
        BENEATH || 3
    }

    def "a run its ceilings stopped at once reads every one, from the top down, the one recorded first, on any page of its tree: #page"() {
        given:
        def deepest = "00000008-0000-4000-8000-000000001108"
        def deeperWorkflow = "00000006-0000-4000-8000-000000001108"
        def deeperVersion = "00000007-0000-4000-8000-000000001108"
        RunRows.workflow(store, deeperWorkflow, deeperVersion, GROUP, "Demo Sample", null, false)
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")
        RunRows.beneath(store, deepest, BENEATH, GROUP, 4, deeperWorkflow, deeperVersion,
                "00000009-0000-4000-8000-000000001108", "0000000a-0000-4000-8000-000000001108")
        RunRows.stoppedByCeiling(store, RUN, RUN, [deepest, BENEATH])

        when:
        def read = read(page == "top" ? RUN : deepest, ANN_USER)

        then:
        read.stop().by() == new Runs.Stopper.ByCeiling([
                new Runs.NumberedRun(new RunId(UUID.fromString(RUN)), 1),
                new Runs.NumberedRun(new RunId(UUID.fromString(BENEATH)), 3),
                new Runs.NumberedRun(new RunId(UUID.fromString(deepest)), 4)])

        where:
        page << ["top", "deepest"]
    }

    def "a stop naming a ceiling beneath another it was made for fails the read rather than name the lower one first"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")
        RunRows.stoppedByCeiling(store, RUN, BENEATH, [RUN])

        when:
        read(RUN, ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message.contains("names run ${BENEATH} beneath another of the ceilings it was made for")
    }

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
        read.from() == new Runs.From(new Runs.NumberedRun(new RunId(UUID.fromString(RUN)), 1),
                new WorkflowStepId(UUID.fromString("00000009-0000-4000-8000-000000001101")), new StepId("step_1"))
        read.workflow() == new Runs.Workflow(new EntryId(UUID.fromString(SUB_WORKFLOW)), new EntryName("Check a claim"), 1)
        read.startedBy() == null
        read.state() == RunState.STOPPED
        read.stop().by() instanceof Runs.Stopper.ByPerson
        read.ceiling() == new Runs.CeilingHeld.AtTop(new Runs.NumberedRun(new RunId(UUID.fromString(RUN)), 1))
        read.acts().isEmpty()

        where:
        reader << [ANN_USER, CAT_USER]
    }

    def "a run two levels beneath names its direct parent and the step that started it"() {
        given:
        def deepest = "00000008-0000-4000-8000-000000001108"
        def deeperWorkflow = "00000006-0000-4000-8000-000000001108"
        def deeperVersion = "00000007-0000-4000-8000-000000001108"
        def startingStep = "00000009-0000-4000-8000-000000001108"
        RunRows.workflow(store, deeperWorkflow, deeperVersion, GROUP, "Demo Sample", null, false)
        StepRows.askingSomebody(store, deeperVersion, GROUP)
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION,
                "00000009-0000-4000-8000-000000001101", "0000000a-0000-4000-8000-000000001101")
        RunRows.beneath(store, deepest, BENEATH, GROUP, 4, deeperWorkflow, deeperVersion, startingStep,
                "0000000a-0000-4000-8000-000000001108")

        when:
        def read = read(deepest, CAT_USER)

        then:
        read.above() == new RunId(UUID.fromString(BENEATH))
        read.from() == new Runs.From(new Runs.NumberedRun(new RunId(UUID.fromString(BENEATH)), 3),
                new WorkflowStepId(UUID.fromString(startingStep)), new StepId("step_1"))
        read.from().run().run() != new RunId(UUID.fromString(RUN))
    }

    def "a run beneath reads every run above it from the top down, a name only at the top: #page, read by #who"() {
        given:
        def deepest = "00000008-0000-4000-8000-000000001108"
        def deeperWorkflow = "00000006-0000-4000-8000-000000001108"
        def deeperVersion = "00000007-0000-4000-8000-000000001108"
        RunRows.workflow(store, deeperWorkflow, deeperVersion, GROUP, "Demo Sample", null, false)
        StepRows.askingSomebody(store, deeperVersion, GROUP)
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION,
                "00000009-0000-4000-8000-000000001101", "0000000a-0000-4000-8000-000000001101")
        RunRows.beneath(store, deepest, BENEATH, GROUP, 4, deeperWorkflow, deeperVersion,
                "00000009-0000-4000-8000-000000001108", "0000000a-0000-4000-8000-000000001108")
        RunRows.beneath(store, "00000008-0000-4000-8000-000000001107", ANNS_RUN, GROUP, 5, SUB_WORKFLOW,
                SUB_VERSION, "00000009-0000-4000-8000-000000001107", "0000000a-0000-4000-8000-000000001107", 2)

        when:
        def above = read(page == "two beneath" ? deepest : BENEATH, reader).runsAbove()

        then:
        above == expected.collect { run, number, name ->
            new Runs.RunAbove(new RunId(UUID.fromString(run)), number, name == null ? null : new RunName(name))
        }
        above*.run().every { it.value() != UUID.fromString(page == "two beneath" ? deepest : BENEATH) }
        above*.number().every { it != 2 && it != 5 }

        where:
        page          | who                 | reader   || expected
        "one beneath" | "its tree's starter" | CAT_USER || [[RUN, 1, "Claim from Ari"]]
        "two beneath" | "its tree's starter" | CAT_USER || [[RUN, 1, "Claim from Ari"], [BENEATH, 3, null]]
        "two beneath" | "an overseer"        | ANN_USER || [[RUN, 1, "Claim from Ari"], [BENEATH, 3, null]]
    }

    def "a run beneath another's tree is refused to one who may read only their own, saying nothing of the runs above"() {
        given:
        RunRows.beneath(store, BENEATH, ANNS_RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION,
                "00000009-0000-4000-8000-000000001101", "0000000a-0000-4000-8000-000000001101")

        when:
        read(BENEATH, CAT_USER)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.RUN_NOT_IN_VIEW
        !refused.message.contains("Claim from Greno")
        !refused.message.contains(ANNS_RUN)
    }

    def "a child with no readable part of its parent step fails the whole read"() {
        given:
        ResultSet row = Mock()
        row.getObject("parent_run_id", UUID.class) >> UUID.fromString(RUN)
        row.getObject("parent_number") >> parentNumber
        row.getObject("parent_step_id", UUID.class) >> parentStep
        row.getString("parent_step_name") >> parentStepName

        when:
        Runs.header(row, new RunId(UUID.fromString(BENEATH)))

        then:
        def failed = thrown(IllegalStateException)
        failed.message.contains(BENEATH)
        failed.message.contains("parent step")

        where:
        parentNumber | parentStep                                                     | parentStepName
        null         | UUID.fromString("00000009-0000-4000-8000-000000001101")        | "step_1"
        1            | null                                                           | "step_1"
        1            | UUID.fromString("00000009-0000-4000-8000-000000001101")        | null
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
                {"kind": "damaged", "sender": {"since": "2026-09-24T10:00:00+02:00", "name": "Ari"},
                 "tags": ["late", "torn"], "noticed": null, "urgent": "true", "amount": "12.50", "claim": "Lost bag"}'''
        def takes = store.transactions().execute {
            OffersHeld.held(store.session, new GroupRoles(store.session).stillReached(CAT_USER, groupId(GROUP),
                    GroupPermission.START_RUN), versionId(version)).orElseThrow().takes()
        }
        def filled = Filling.of(takes, ConstantJson.sent(request)) as FilledFields
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                                  started_with, created_by)
                values (?::uuid, ?::uuid, 4, 'Claim from Arlo', ?::uuid, ?::uuid, ?::uuid, 0, ?::jsonb, ?::uuid)
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

    def "a run beneath another reads as started with none"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, "00000009-0000-4000-8000-000000001101",
                "0000000a-0000-4000-8000-000000001101")

        expect:
        read(BENEATH, ANN_USER).startedWith() == null
        read(BENEATH, ANN_USER).startedWithFrom() == null
    }

    def "a child run reads the value and source bound from the run above"() {
        given:
        def parentStep = "00000009-0000-4000-8000-000000001101"
        def parentRunStep = "0000000a-0000-4000-8000-000000001101"
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: VERSION, ownerKind: "workflow",
                               side: "takes", position: 1, name: "ticket"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: SUB_VERSION, ownerKind: "workflow",
                               side: "takes", position: 1, name: "item"])
        // DB-SPECIFIC: PostgreSQL ::uuid casts, jsonb type.
        store.session.sql("update runs set started_with = cast(? as jsonb) where run_id = ?::uuid")
                .params('{"ticket":"Demo Sample ticket"}', RUN).update()
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION, parentStep, parentRunStep)
        StepRows.binding(store, UUID.randomUUID().toString(), VERSION, parentStep, "item", null, "ticket")

        when:
        def read = read(BENEATH, ANN_USER)

        then:
        read.startedWith() == JsonMapper.builder().build().readTree('{"item":"Demo Sample ticket"}')
        read.startedWithFrom().size() == 1
        read.startedWithFrom()[0].input() == "item"
        read.startedWithFrom()[0].from().kind() == "run_input"
        read.startedWithFrom()[0].from().path() == "ticket"
        read.startedWithFrom()[0].from().runId() == UUID.fromString(RUN)
        read.startedWithFrom()[0].sources() == null
    }

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
        read(RUN, ANN_USER).spend() == new RunBudget.Spend(190, 25, true, 4, 4)
        read(RUN, ANN_USER).spend().spent() == 215
        read(BENEATH, ANN_USER).spend() == new RunBudget.Spend(10, 5, false, 1, 1)
    }

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

    def "a raise waiting on a stopped run is offered for deciding all the same"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 10)
        RunRows.stopped(store, RUN, CAT)

        expect:
        read(RUN, ANN_USER).acts() == [OPEN_AGAIN, RENAME, CHANGE_CEILING, APPROVE_RAISE, REFUSE_RAISE] as Set
    }

    def "a raise decided is no longer waiting, and only an approved one is in force"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 5000L, true, CAT, 10)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), enum type run_ceiling_change_outcome.
        store.session.sql("""
                update run_ceiling_changes set outcome = cast(? as run_ceiling_change_outcome), decided_at = now(),
                       decided_by = ?::uuid
                """).params(outcome, outcome == "withdrawn_by_requester" ? CAT : ANN).update()

        when:
        def read = read(RUN, ANN_USER)

        then:
        read.ceiling() == new Runs.CeilingHeld.Own(new RunBudget.InForce(new Ceiling(inForce), true), null)
        read.acts() == [STOP, RENAME, CHANGE_CEILING] as Set

        where:
        outcome     || inForce
        "approved"  || 5000
        "refused"   || 1000
        "withdrawn_by_requester" || 1000
    }

    def "a run with no ceiling reads as one that may spend without limit"() {
        when:
        def read = runs.run(groupId(OTHER_GROUP), new RunId(UUID.fromString(OTHER_GROUPS_RUN)), ANN_USER)

        then:
        read.ceiling() == new Runs.CeilingHeld.Own(new RunBudget.InForce(null, false), null)
    }

    // Use the same refusal for missing, foreign, and unreadable runs to avoid revealing their existence.
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

    def "a stored name the run's type refuses fails the read, naming the run"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update runs set name = ' ' where run_id = ?::uuid").params(RUN).update()

        when:
        read(RUN, ANN_USER)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Run ${RUN} holds a name this system will not show"
    }

    // JSONB keeps only the last value for each duplicate object key.
    def "what a run was started with stored in no shape its version takes fails the read, naming the run: #stored"() {
        given:
        taken(VERSION, [position: 1, name: "amount", kind: "number"])
        taken(VERSION, [position: 2, name: "tags", most: 5])
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
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
    def "a stop and its opening remain separate dated events with unique keys and their actual authors"() {
        given:
        RunRows.stopped(store, RUN, CAT)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update run_stops set opened_again_at = now(), opened_again_by = ?::uuid where run_id = ?::uuid")
                .params(ANN, RUN).update()

        when:
        def events = read(RUN, ANN_USER).conversationEvents()

        then:
        events*.kind() == ["stopped", "opened_again"]
        events*.id().toSet().size() == 2
        events*.run() == [new RunActs.NumberedRun(UUID.fromString(RUN), 1)] * 2
        events[0].by().userId() == CAT_USER.value()
        events[1].by().userId() == ANN_USER.value()
        events.every { it.ceilings() == null && it.stepId() == null && it.why() == null }
    }

    def "a child conversation keeps root stops and openings only from after the child began"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update runs set created_at = now() - interval '10 minutes' where run_id = ?::uuid")
                .params(RUN).update()
        RunRows.stopped(store, RUN, CAT)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_stops set created_at = now() - interval '4 minutes',
                       opened_again_at = now() - interval '3 minutes', opened_again_by = ?::uuid
                 where run_id = ?::uuid
                """).params(ANN, RUN).update()
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION,
                UUID.randomUUID().toString(), UUID.randomUUID().toString())
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update runs set created_at = now() - interval '2 minutes' where run_id = ?::uuid")
                .params(BENEATH).update()
        RunRows.stopped(store, RUN, ANN)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_stops set created_at = now() - interval '1 minute',
                       opened_again_at = now(), opened_again_by = ?::uuid
                 where run_id = ?::uuid and opened_again_at is null
                """).params(CAT, RUN).update()

        when:
        def rootEvents = read(RUN, ANN_USER).conversationEvents().findAll {
            it.kind() in ["stopped", "opened_again"]
        }
        def childEvents = read(BENEATH, ANN_USER).conversationEvents().findAll {
            it.kind() in ["stopped", "opened_again"]
        }

        then:
        rootEvents*.kind() == ["stopped", "opened_again", "stopped", "opened_again"]
        childEvents*.kind() == ["stopped", "opened_again"]
        childEvents*.id() == rootEvents.drop(2)*.id()
        childEvents*.by()*.userId() == [ANN_USER.value(), CAT_USER.value()]
        !childEvents.any { it.id() in rootEvents.take(2)*.id() }
    }

    def "a top conversation includes a child ceiling request and its result with the child's number"() {
        given:
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION,
                "00000009-0000-4000-8000-000000001101", "0000000a-0000-4000-8000-000000001101")
        RunRows.ceilingChanged(store, RAISE, BENEATH, 300L, 500L, true, CAT, 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_ceiling_changes set outcome = 'approved', decided_at = now(), decided_by = ?::uuid
                 where run_ceiling_change_id = ?::uuid
                """).params(ANN, RAISE).update()

        when:
        def events = read(RUN, ANN_USER).conversationEvents()
        def ceiling = events.findAll { it.kind().startsWith("ceiling_") }

        then:
        ceiling*.kind() == ["ceiling_asked", "ceiling_approved"]
        ceiling*.run() == [new RunActs.NumberedRun(UUID.fromString(BENEATH), 3)] * 2
        ceiling*.from() == ["300", "300"]
        ceiling*.to() == ["500", "500"]
        ceiling*.by()*.userId() == [CAT_USER.value(), ANN_USER.value()]
        ceiling*.id().toSet().size() == 2
        events.any { it.kind() == "step_reached" && it.stepId() == UUID.fromString("00000009-0000-4000-8000-000000001101") }
    }

    def "a child conversation includes its own and descendants' ceiling acts but none from beside it"() {
        given:
        def beside = UUID.randomUUID().toString()
        def descendant = UUID.randomUUID().toString()
        def descendantEntry = UUID.randomUUID().toString()
        def descendantVersion = UUID.randomUUID().toString()
        RunRows.workflow(store, descendantEntry, descendantVersion, GROUP, "Check another detail", 200L, false, true)
        RunRows.beneath(store, BENEATH, RUN, GROUP, 3, SUB_WORKFLOW, SUB_VERSION,
                UUID.randomUUID().toString(), UUID.randomUUID().toString())
        RunRows.beneath(store, beside, RUN, GROUP, 4, SUB_WORKFLOW, SUB_VERSION,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2)
        RunRows.beneath(store, descendant, BENEATH, GROUP, 5, descendantEntry, descendantVersion,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), RUN, 1000L, 900L, false, CAT, 4)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), BENEATH, 300L, 250L, false, CAT, 3)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), beside, 300L, 240L, false, CAT, 2)
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), descendant, 200L, 180L, false, CAT, 1)
        def statements = []
        def counted = JdbcClient.create(new CountingDataSource(store.database, statements))
        def current = new RunActs.NumberedRun(UUID.fromString(BENEATH), 3)
        def root = new RunActs.NumberedRun(UUID.fromString(RUN), 1)
        def snapshot = new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([]))
                .asRead(groupId(GROUP), new RunId(UUID.fromString(BENEATH)))

        when:
        def direct = RunActs.read(counted, current, root, UUID.fromString(GROUP), snapshot)
        def page = read(BENEATH, ANN_USER).conversationEvents()

        then:
        statements.size() == 5
        direct.findAll { it.kind() == "ceiling_changed" }*.run() ==
                [current, new RunActs.NumberedRun(UUID.fromString(descendant), 5)]
        page.findAll { it.kind() == "ceiling_changed" }*.run() ==
                [current, new RunActs.NumberedRun(UUID.fromString(descendant), 5)]
        !page.any { it.run()?.runId() == UUID.fromString(RUN) && it.kind().startsWith("ceiling_") }
        !page.any { it.run()?.runId() == UUID.fromString(beside) && it.kind().startsWith("ceiling_") }
    }

    def "event reading uses the same five statements for one reached step and two"() {
        given:
        def second = UUID.randomUUID().toString()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def pinned = store.texts("select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid", ASKING)[0]
        def system = RunRows.WORKFLOW_RUNNER
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by)
                select ?::uuid, entry_version_id, workflow_step_id, kind, pinned_version_id, pinned_kind,
                       producer, reviewed_by_model, tries, ?::uuid
                  from workflow_steps where workflow_step_id = ?::uuid
                """).params(RUN, system, ASKING).update()
        def statements = []
        def counted = JdbcClient.create(new CountingDataSource(store.database, statements))
        def current = new RunActs.NumberedRun(UUID.fromString(RUN), 1)
        def snapshot = new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([]))
                .asRead(groupId(GROUP), new RunId(UUID.fromString(RUN)))

        when:
        def one = RunActs.read(counted, current, current, UUID.fromString(GROUP), snapshot)
        def firstCount = statements.size()
        statements.clear()
        StepRows.step(store, second, VERSION, 1, "follow_up", pinned, "person", 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by)
                select ?::uuid, entry_version_id, workflow_step_id, kind, pinned_version_id, pinned_kind,
                       producer, reviewed_by_model, tries, ?::uuid
                  from workflow_steps where workflow_step_id = ?::uuid
                """).params(RUN, system, second).update()
        snapshot = new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([]))
                .asRead(groupId(GROUP), new RunId(UUID.fromString(RUN)))
        def two = RunActs.read(counted, current, current, UUID.fromString(GROUP), snapshot)

        then:
        one*.kind() == ["step_reached"]
        two*.kind() == ["step_reached", "step_reached"]
        firstCount == 5
        statements.size() == 5
    }

    def "one person's review keeps every value's separate decision and words in one event"() {
        given:
        def secondField = UUID.randomUUID().toString()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set standing = 'never' where declaration_field_id = ?::uuid")
                .params(answer).update()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def pinned = store.texts("select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid", ASKING)[0]
        StepRows.field(store, [id: secondField, owner: pinned, ownerKind: "question", side: "gives",
                               position: 2, name: "note", mustBe: true, standing: "never"])
        def executor = EngineExecutors.of(store.database)
        def tree = new RunTree(store.session)
        def writes = new EngineWrites(store.session)
        def snapshots = new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([]))
        def ceilings = new CeilingReach(store.session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new org.lilradish.lite.app.codestep.CodeRuns(
                        org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE), store.transactions(), tree,
                        snapshots, writes, executor, heldFiles), DeployedModels.HELD, ceilings,
                new GroupRoles(store.session), Deployments.DEEP_ENOUGH, heldFiles).drive(groupId(GROUP), new RunId(UUID.fromString(RUN)))
        executor.destroy()
        StepRows.answered(store, ASKING, CAT, "I checked it.", [(answer): '"Fine."', (secondField): '"Check."'])
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def review = store.session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by,
                                     reviewed_by_model, created_by, created_by_kind)
                select tried.production_id, tried.run_step_id, tried.root_run_id, tried.ended_by,
                       tried.reviewed_by_model, ?::uuid, 'person'
                  from productions tried join run_steps step on step.run_step_id = tried.run_step_id
                 where step.workflow_step_id = ?::uuid
                returning review_id::text
                """).params(ANN, ASKING).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type review_outcome.
        store.session.sql("""
                insert into review_decisions (review_id, production_value_id, production_id, for_length,
                                              value_needs_review, outcome, explanation)
                select ?::uuid, value.production_value_id, value.production_id, false, value.needs_review,
                       cast(? as review_outcome), ?
                  from production_values value
                 where value.declaration_field_id = ?::uuid
                """).params(review, "assured", null, answer).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type review_outcome.
        store.session.sql("""
                insert into review_decisions (review_id, production_value_id, production_id, for_length,
                                              value_needs_review, outcome, explanation)
                select ?::uuid, value.production_value_id, value.production_id, false, value.needs_review,
                       cast(? as review_outcome), ?
                  from production_values value
                 where value.declaration_field_id = ?::uuid
                """).params(review, "refused", "Needs another source.", secondField).update()

        when:
        def events = read(RUN, ANN_USER).conversationEvents()
        def reviewed = events.findAll { it.kind() == "reviewed" }

        then:
        reviewed.size() == 1
        reviewed[0].by().userId() == ANN_USER.value()
        reviewed[0].stepId() == UUID.fromString(ASKING)
        reviewed[0].number() == 1
        reviewed[0].decisions() == [new RunActs.Decision("answer", "assured", null),
                                     new RunActs.Decision("note", "refused", "Needs another source.")]
        reviewed[0].decisions()*.outcome() == [ReviewOutcome.ASSURED.published(), ReviewOutcome.REFUSED.published()]
        reviewed[0].values() == null
        !events.any { it.kind() == "refused_for_length" }
    }

    def "a requester withdrawal stays that person's event after somebody later changes the ceiling"() {
        given:
        RunRows.ceilingChanged(store, RAISE, RUN, 1000L, 2000L, true, CAT, 2)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update run_ceiling_changes set outcome = 'withdrawn_by_requester', decided_at = now() - interval '1 minute',
                                               decided_by = ?::uuid
                 where run_ceiling_change_id = ?::uuid
                """).params(CAT, RAISE).update()
        RunRows.ceilingChanged(store, UUID.randomUUID().toString(), RUN, 1000L, 900L, false, ANN, 0)

        when:
        def events = read(RUN, ANN_USER).conversationEvents()
        def ceiling = events.findAll { it.kind().startsWith("ceiling_") }

        then:
        ceiling*.kind() == ["ceiling_asked", "ceiling_withdrawn", "ceiling_changed"]
        ceiling[1].by().userId() == CAT_USER.value()
        ceiling[2].by().userId() == ANN_USER.value()
        !ceiling.any { it.kind() == "ceiling_superseded" }
    }

    def "a refusal for length names the held step as a separate act, even by the value's producer"() {
        given:
        def heldStep = UUID.randomUUID().toString()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def pinned = store.texts("select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid", ASKING)[0]
        StepRows.step(store, heldStep, VERSION, 1, "follow_up", pinned, "model", 1)
        [ASKING, heldStep].each { planned ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                           pinned_kind, producer, reviewed_by_model, tries, created_by)
                    select ?::uuid, entry_version_id, workflow_step_id, kind, pinned_version_id, pinned_kind,
                           producer, reviewed_by_model, tries, ?::uuid
                      from workflow_steps where workflow_step_id = ?::uuid
                    """).params(RUN, RunRows.WORKFLOW_RUNNER, planned).update()
        }
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def answered = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                select step.run_step_id, step.run_id, ?::uuid, step.kind, step.producer,
                       step.reviewed_by_model, step.tries, step.pinned_version_id, 1, 'person',
                       ?::uuid, 'person', now(), ?::uuid, 'person', 'I checked it.'
                  from run_steps step where step.workflow_step_id = ?::uuid and step.run_id = ?::uuid
                returning production_id::text
                """).params(RUN, CAT, CAT, ASKING, RUN).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts.
        store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                select ?::uuid, tried.run_step_kind, tried.producer, tried.pinned_version_id,
                       field.declaration_field_id, field.standing, '"A long answer."'::jsonb
                  from productions tried join declaration_fields field on field.declaration_field_id = ?::uuid
                 where tried.production_id = ?::uuid
                """).params(answered, answer, answered).update()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def modelTry = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                select step.run_step_id, step.run_id, ?::uuid, step.kind, step.producer,
                       step.reviewed_by_model, step.tries, step.pinned_version_id, 1, 'model', ?::uuid
                  from run_steps step where step.workflow_step_id = ?::uuid and step.run_id = ?::uuid
                returning production_id::text
                """).params(RUN, RunRows.WORKFLOW_RUNNER, heldStep, RUN).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def attempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id,
                                                    purpose, model, mode, production_id, too_long, payload,
                                                    created_by, created_by_kind)
                select step.run_step_id, step.run_id, step.kind, step.workflow_step_id, 'produce',
                       'general', 'ordinary', ?::uuid, true, '{}', ?::uuid, 'system'
                  from run_steps step where step.workflow_step_id = ?::uuid and step.run_id = ?::uuid
                returning run_step_send_attempt_id::text
                """).params(modelTry, RunRows.WORKFLOW_RUNNER, heldStep, RUN).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def hold = store.session.sql("""
                insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                            run_step_send_attempt_id, created_by, created_by_kind)
                select step.run_step_id, step.run_id, step.kind, step.calls_a_model, 'too_long',
                       ?::uuid, ?::uuid, 'system'
                  from run_steps step where step.workflow_step_id = ?::uuid and step.run_id = ?::uuid
                returning run_step_hold_id::text
                """).params(attempt, RunRows.WORKFLOW_RUNNER, heldStep, RUN).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def review = store.session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by,
                                     reviewed_by_model, run_step_hold_id, hold_run_id, created_by)
                select tried.production_id, tried.run_step_id, tried.root_run_id, tried.ended_by,
                       tried.reviewed_by_model, ?::uuid, ?::uuid, ?::uuid
                  from productions tried where tried.production_id = ?::uuid
                returning review_id::text
                """).params(hold, RUN, CAT, answered).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into review_decisions (review_id, production_value_id, production_id, for_length,
                                              value_needs_review, outcome, explanation)
                select ?::uuid, value.production_value_id, value.production_id, true, value.needs_review,
                       'refused', 'The next message would be too long.'
                  from production_values value where value.production_id = ?::uuid
                """).params(review, answered).update()

        when:
        def current = new RunActs.NumberedRun(UUID.fromString(RUN), 1)
        def snapshot = new RunSnapshots(store.session, org.lilradish.lite.testutil.codestep.CodeStepsHeld.NONE, new SearchWays([]))
                .asRead(groupId(GROUP), new RunId(UUID.fromString(RUN)))
        def events = RunActs.read(store.session, current, current, UUID.fromString(GROUP), snapshot)
        def length = events.findAll { it.kind() == "refused_for_length" }

        then:
        length.size() == 1
        length[0].by().userId() == CAT_USER.value()
        length[0].stepId() == UUID.fromString(ASKING)
        length[0].held() == new RunActs.Held(current, UUID.fromString(heldStep), "follow_up")
        length[0].decisions() == [new RunActs.Decision("answer", "refused", "The next message would be too long.")]
        length[0].decisions()*.outcome() == [ReviewOutcome.REFUSED.published()]
        !events.any { it.kind() == "reviewed" }
    }

}
