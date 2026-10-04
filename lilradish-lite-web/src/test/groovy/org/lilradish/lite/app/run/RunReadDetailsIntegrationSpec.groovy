package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.app.run.fixture.TreeWorkflows
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.run.InputRecord
import org.lilradish.lite.domain.run.ProductionValueId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepInputs
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class RunReadDetailsIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    String rootEntry
    String rootVersion
    String childEntry
    String childVersion
    String rootRun
    String childRun
    String childStep
    String parentStep
    String parentRunStep
    String parentBinding

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "run_read_details_" + (++databasesMade))
        TicketWorkflow.seed(store)
        rootEntry = UUID.randomUUID().toString()
        rootVersion = UUID.randomUUID().toString()
        childEntry = UUID.randomUUID().toString()
        childVersion = UUID.randomUUID().toString()
        rootRun = UUID.randomUUID().toString()
        childRun = UUID.randomUUID().toString()
        childStep = UUID.randomUUID().toString()
        parentStep = UUID.randomUUID().toString()
        parentRunStep = UUID.randomUUID().toString()
        parentBinding = UUID.randomUUID().toString()
        StepRows.workflow(store, rootEntry, rootVersion, GROUP, "Demo Sample root")
        StepRows.workflow(store, childEntry, childVersion, GROUP, "Demo Sample child")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: rootVersion, ownerKind: "workflow",
                               side: "takes", position: 1, name: "ticket", kind: "fields"])
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def rootField = store.texts("select declaration_field_id::text from declaration_fields where entry_version_id = ?::uuid and name = 'ticket'", rootVersion)[0]
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: rootVersion, ownerKind: "workflow",
                               side: "takes", parent: rootField, position: 1, name: "first"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: rootVersion, ownerKind: "workflow",
                               side: "takes", parent: rootField, position: 2, name: "later"])
        def item = UUID.randomUUID().toString()
        StepRows.field(store, [id: item, owner: childVersion, ownerKind: "workflow",
                               side: "takes", position: 1, name: "item", kind: "fields"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "takes", parent: item, position: 2, name: "first"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: "workflow",
                               side: "takes", parent: item, position: 1, name: "later"])
        StepRows.askingSomebody(store, childVersion, GROUP, childStep)
        StepRows.run(store, rootRun, GROUP, 71, rootEntry, rootVersion, CAT,
                '{"ticket":{"first":"Demo Sample first","later":"Demo Sample later"}}')
        RunRows.beneath(store, childRun, rootRun, GROUP, 72, childEntry, childVersion,
                parentStep, parentRunStep)
        StepRows.binding(store, parentBinding, rootVersion, parentStep, "item", null, "ticket")
    }

    private def context() {
        new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
                .inTreeAsRead(groupId(GROUP), new RunId(UUID.fromString(childRun)))
    }

    def "child identity and deepest pending step come from its own run"() {
        given:
        def child = context()
        def details = new RunReadDetails(store.session, child)

        when:
        def beneath = details.beneath(child.from())

        then:
        beneath.run() == new RunReadDetails.NumberedRunRecord(new RunId(UUID.fromString(childRun)), 72)
        beneath.at().run() == beneath.run()
        beneath.at().stepId() == UUID.fromString(childStep)
        beneath.at().name() == "ask"
        beneath.workflow() == new RunReadDetails.Workflow(UUID.fromString(childEntry), "Demo Sample child", 1,
                UUID.fromString(childVersion))
        details.createdAt(child.run().run()) != null
        details.numbered(child.above().run().run()).number() == 71
        details.costBeneath([child.from()])[child.run().run()] == RunBudget.Spend.NOTHING
    }

    def "child input follows the top run while composite parts keep declaration order and repeated origin"() {
        given:
        def child = context()
        def details = new RunReadDetails(store.session, child)
        def binding = new Binding(UUID.randomUUID(), Pointer.parse("item"),
                new BindingSource.WorkflowInput(Pointer.parse("ticket")))
        def parts = [new InputRecord.Part(Pointer.parse("later").names(), null),
                     new InputRecord.Part(Pointer.parse("first").names(), null)]

        when:
        def read = details.parts(child.above(), child.run().workflow().takes(), binding, parts)

        then:
        read*.targetPath() == ["later", "first"]
        read*.from()*.kind() == ["run_input", "run_input"]
        read*.from()*.path() == ["ticket.later", "ticket.first"]
        read*.from()*.run() == [new RunReadDetails.NumberedRunRecord(new RunId(UUID.fromString(rootRun)), 71)] * 2
        !read.any { it.from().maker() != null }
    }

    def "a child start reads the stored parent binding and keeps the actual root input identity"() {
        given:
        def child = context()
        def details = new RunReadDetails(store.session, child)

        when:
        def started = details.startedWith(child)

        then:
        started*.input() == ["item"]
        started[0].value() instanceof JsonValue.JsonObject
        started[0].from().kind() == "run_input"
        started[0].from().path() == "ticket"
        started[0].from().run() == new RunReadDetails.NumberedRunRecord(new RunId(UUID.fromString(rootRun)), 71)
        started[0].from().maker() == null
        started[0].parts() == []
    }

    def "a chosen route case starts its child with the same bound value and source"() {
        given:
        TreeWorkflows.seed(store)
        store.termField(UUID.randomUUID().toString(), TreeWorkflows.GOING_VERSION, "workflow",
                TreeWorkflows.TERMS_VERSION, "takes")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into bindings (entry_version_id, route_case_id, target_path, source_path, created_by)
                values (?::uuid, ?::uuid, 'category', 'category', ?::uuid)
                """).params(TreeWorkflows.ROUTED_VERSION, TreeWorkflows.GO_CASE, SEEDER).update()
        def wiring = TreeWorkflows.wired(store, 32)
        store.transactions().executeWithoutResult {
                TreeWorkflows.run(store, TreeWorkflows.ROUTED, TreeWorkflows.ROUTED_VERSION,
                        '{"category":"go"}')
                wiring.engine.planStarted(wiring.tree.lock(groupId(TreeWorkflows.GROUP),
                        new RunId(UUID.fromString(TreeWorkflows.ROOT))).orElseThrow())
            }
            // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
            def childRun = store.texts("select run_id::text from runs where parent_run_id = ?::uuid",
                    TreeWorkflows.ROOT)[0]
            def child = wiring.snapshots.inTreeAsRead(groupId(TreeWorkflows.GROUP),
                    new RunId(UUID.fromString(childRun)))
            def details = new RunReadDetails(store.session, child)

            when:
            def started = details.startedWith(child)
            def beneath = details.beneath(child.from())

            then:
            started*.input() == ["category"]
            started[0].value() == new JsonValue.JsonString("go")
            started[0].from().kind() == "run_input"
            started[0].from().path() == "category"
            started[0].from().run() == details.numbered(child.above().run().run())
            beneath.workflow().versionId() == UUID.fromString(TreeWorkflows.GOING_VERSION)
            child.from().planned().runs() instanceof org.lilradish.lite.domain.run.StepRuns.Route

        cleanup:
        wiring.executor.destroy()
    }

    def "a carried start value is omitted from its log rendering while identity stays visible"() {
        given:
        def origin = new RunReadDetails.OriginRecord("run_input", "ticket",
                new RunReadDetails.NumberedRunRecord(new RunId(UUID.fromString(rootRun)), 71), null)
        def started = new RunReadDetails.StartedInputRecord("item",
                new JsonValue.JsonString("Demo Sample private"), origin, [])

        when:
        def rendered = started.toString()

        then:
        rendered.contains("StartedInputRecord(")
        rendered.contains("input=item")
        rendered.contains("from=")
        started.from().path() == "ticket"
        rendered.contains("OriginRecord(kind=run_input, path=ticket")
        rendered.contains("NumberedRunRecord(")
        !rendered.contains("Demo Sample private")
        !rendered.contains("StartedInputRecord(input=item, value=")
    }

    def "stored composite parts keep their final origins and declaration order"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def questionVersion = store.texts("select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid",
                childStep)[0]
        def questionItem = UUID.randomUUID().toString()
        StepRows.field(store, [id: questionItem, owner: questionVersion, ownerKind: "question",
                               side: "takes", position: 1, name: "item", kind: "fields"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                               side: "takes", parent: questionItem, position: 1, name: "later"])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: "question",
                               side: "takes", parent: questionItem, position: 2, name: "first"])
        def questionBinding = UUID.randomUUID().toString()
        StepRows.binding(store, questionBinding, childVersion, childStep, "item", null, "item")
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def heldStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                       created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'question', 'person', false, 1,
                        ?::uuid, 'system') returning run_step_id::text
                """).params(childRun, childVersion, childStep, questionVersion, RunRows.WORKFLOW_RUNNER)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 1, ?::uuid, 1, 'person',
                        ?::uuid, 'system') returning production_id::text
                """).params(heldStep, childRun, rootRun, questionVersion, RunRows.WORKFLOW_RUNNER)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def input = store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                               workflow_step_id, binding_id, binding_reads_a_constant)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'question', ?::uuid, ?::uuid, false)
                returning production_input_id::text
                """).params(production, childRun, rootRun, heldStep, childStep, questionBinding)
                .query(String).single()
        ["first", "later"].each { part ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into production_input_parts (production_input_id, root_run_id, target_path)
                    values (?::uuid, ?::uuid, ?)
                    """).params(input, rootRun, part).update()
        }
        def child = context()
        def details = new RunReadDetails(store.session, child)
        def saved = child.run().steps()[0].tries()[0].inputs()[0]
        def binding = child.run().steps()[0].planned().bindings()[0]

        when:
        def ordered = details.parts(child, child.run().steps()[0].planned().runs().takes(), binding, saved.parts())

        then:
        saved.parts()*.targetPath()*.first()*.value() == ["first", "later"]
        ordered*.targetPath() == ["later", "first"]
        ordered*.from()*.kind() == ["run_input", "run_input"]
        ordered*.from()*.path() == ["ticket.later", "ticket.first"]
        !ordered.any { it.from().maker() != null }
    }

    def "a value made two runs down keeps its maker and its call counts in the root subtree"() {
        given:
        def topEntry = UUID.randomUUID().toString()
        def topVersion = UUID.randomUUID().toString()
        def middleEntry = UUID.randomUUID().toString()
        def middleVersion = UUID.randomUUID().toString()
        def deepEntry = UUID.randomUUID().toString()
        def deepVersion = UUID.randomUUID().toString()
        def topRun = UUID.randomUUID().toString()
        def middleRun = UUID.randomUUID().toString()
        def deepRun = UUID.randomUUID().toString()
        def topStep = UUID.randomUUID().toString()
        def deepStep = UUID.randomUUID().toString()
        StepRows.workflow(store, topEntry, topVersion, GROUP, "Demo Sample top")
        StepRows.workflow(store, middleEntry, middleVersion, GROUP, "Demo Sample middle")
        StepRows.workflow(store, deepEntry, deepVersion, GROUP, "Demo Sample deep")
        def answer = StepRows.askingSomebody(store, deepVersion, GROUP, deepStep)
        StepRows.run(store, topRun, GROUP, 81, topEntry, topVersion, CAT, '{}')
        RunRows.beneath(store, middleRun, topRun, GROUP, 82, middleEntry, middleVersion,
                topStep, UUID.randomUUID().toString())
        RunRows.beneath(store, deepRun, middleRun, GROUP, 83, deepEntry, deepVersion,
                UUID.randomUUID().toString(), UUID.randomUUID().toString())
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def questionVersion = store.texts("select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid",
                deepStep)[0]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, RETURNING.
        def heldStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind,
                                       pinned_version_id, pinned_kind, producer, reviewed_by_model, tries,
                                       created_by, created_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'question', 'person', false, 1,
                        ?::uuid, 'system') returning run_step_id::text
                """).params(deepRun, deepVersion, deepStep, questionVersion, RunRows.WORKFLOW_RUNNER)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts, now(), RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, created_by_kind, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'person', false, 1, ?::uuid, 1, 'person',
                        ?::uuid, 'system', now(), ?::uuid, 'person', 'Demo Sample')
                returning production_id::text
                """).params(heldStep, deepRun, topRun, questionVersion, RunRows.WORKFLOW_RUNNER, CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::text/::uuid casts, RETURNING.
        def value = store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'always', '"Demo Sample"'::jsonb)
                returning production_value_id::text
                """).params(production, questionVersion, answer).query(String).single()
        RunRows.called(store, deepRun, "came_back", 5, 2)
        RunRows.called(store, middleRun, null, 3, null)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update model_calls set counted_by_model = true where run_id = ?::uuid")
                .param(deepRun).update()
        def context = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
                .inTreeAsRead(groupId(GROUP), new RunId(UUID.fromString(middleRun)))
        def details = new RunReadDetails(store.session, context)
        def rootStep = context.above().run().steps()[0]

        when:
        def maker = details.maker(new org.lilradish.lite.domain.run.ProductionValueId(UUID.fromString(value)))
        def nestedStep = context.run().steps()[0]
        def spendByRun = details.costBeneath([rootStep, nestedStep])
        def spend = spendByRun[context.run().run()]
        def deepSpend = spendByRun[new RunId(UUID.fromString(deepRun))]

        then:
        maker.run() == new RunReadDetails.NumberedRunRecord(new RunId(UUID.fromString(deepRun)), 83)
        maker.stepId() == UUID.fromString(deepStep)
        maker.field() == "answer"
        maker.toString().contains("stepId=")
        !maker.toString().contains("Demo Sample")
        maker.run() != details.numbered(context.run().run())
        spend == RunBudget.spentAsRead(store.session, context.run().run(), false)
        deepSpend == RunBudget.spentAsRead(store.session, new RunId(UUID.fromString(deepRun)), false)
        spend.totalCalls() == 2
        spend.sent() == 8
        spend.cameBack() == 2
        spend.cameBackUnknown()
        spend.measuredHere() == RunBudget.Measurement.PARTLY
        spend.spent() == 10
        deepSpend.spent() == 7
        deepSpend.measuredHere() == null
    }

    def "unknown maker and an undeclared component fail without disclosing another tree"() {
        given:
        def child = context()
        def details = new RunReadDetails(store.session, child)
        def binding = new Binding(UUID.randomUUID(), Pointer.parse("item"),
                new BindingSource.WorkflowInput(Pointer.parse("ticket")))

        when:
        details.maker(new org.lilradish.lite.domain.run.ProductionValueId(UUID.randomUUID()))

        then:
        thrown(IllegalStateException)
        details.numbered(child.run().run()).number() == 72

        when:
        details.parts(child.above(), child.run().workflow().takes(), binding,
                [new InputRecord.Part(Pointer.parse("missing").names(), null)])

        then:
        thrown(IllegalStateException)
        details.numbered(child.above().run().run()).number() == 71
    }

    def "batch subtree spending matches individual reads for child count"() {
        given:
        def childRuns = [childRun]
        for (int position = 2; position <= count; position++) {
            def step = UUID.randomUUID().toString()
            def runStep = UUID.randomUUID().toString()
            def run = UUID.randomUUID().toString()
            store.step(step, rootVersion, position, childVersion, "workflow")
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind,
                                           pinned_version_id, pinned_kind, reviewed_by_model, created_by)
                    values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'workflow', false, ?::uuid)
                    """).params(runStep, rootRun, rootVersion, step, childVersion, RunRows.WORKFLOW_RUNNER).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into runs (run_id, group_id, number, entry_id, entry_version_id, parent_run_id,
                                      parent_run_step_id, parent_run_step_kind, parent_workflow_step_id, root_run_id,
                                      depth, parent_depth, created_by, created_by_kind)
                    values (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?::uuid, ?::uuid, 'workflow', ?::uuid,
                            ?::uuid, 1, 0, ?::uuid, 'system')
                    """).params(run, GROUP, 72 + position, childEntry, childVersion, rootRun,
                    runStep, step, rootRun, RunRows.WORKFLOW_RUNNER).update()
            childRuns << run
        }
        RunRows.called(store, childRuns[0], "came_back", 5, 2)
        if (count > 2) {
            RunRows.called(store, childRuns[2], null, 3, null)
        }
        if (count > 3) {
            RunRows.called(store, childRuns[3], "turned_away", 1, null)
        }
        def top = context().above()
        def statements = []
        def counting = new CountingDataSource(store.database, statements)
        def counted = JdbcClient.create(counting)
        def details = new RunReadDetails(counted, top)
        statements.clear()

        when:
        def batch = details.costBeneath(top.run().steps())
        def batchQueries = statements.size()
        statements.clear()
        def individual = childRuns.collectEntries { run ->
            def id = new RunId(UUID.fromString(run))
            [(id): RunBudget.spentAsRead(counted, id, false)]
        }
        def individualQueries = statements.size()
        def pageReader = new RunSteps(counted, new GroupRoles(counted),
                new RunSnapshots(counted, CodeStepsHeld.NONE, new SearchWays([])),
                store.transactionManager(counting), Deployments.DEEP_ENOUGH, new org.lilradish.lite.app.currency.GroupCurrencies(counted, new org.springframework.transaction.support.TransactionTemplate(store.transactionManager(counting)), new GroupRoles(counted), org.lilradish.lite.testutil.library.DeployedModels.HELD))
        statements.clear()
        def page = pageReader.steps(groupId(GROUP), new RunId(UUID.fromString(rootRun)), CAT_USER)
        def pageCostQueries = statements.count { it.contains("with recursive subtree (child_run_id, run_id)") }
        def pageMemberQueries = statements.count { it.contains("entry.name as workflow_name") }

        then:
        batch == individual
        batchQueries == 1
        individualQueries == count
        pageCostQueries == 1
        pageMemberQueries == 1
        page.steps()*.beneath()*.runId() == childRuns.collect { UUID.fromString(it) }
        page.steps()[0].cost().spent() == "7"
        if (count > 1) {
            assert !page.steps()[1].cost().callsAModel()
        }
        batch[new RunId(UUID.fromString(childRuns[0]))].spent() == 7
        if (count > 1) {
            assert batch[new RunId(UUID.fromString(childRuns[1]))] == RunBudget.Spend.NOTHING
        }
        if (count > 2) {
            assert batch[new RunId(UUID.fromString(childRuns[2]))].cameBackUnknown()
        }

        where:
        count << [1, 2, 20]
    }
    def "a real read source follows a child input to its root and orders its component origins"() {
        given:
        def child = context()
        def details = new RunReadDetails(store.session, child)
        def read = new SearchQuestionPart.Read(UUID.randomUUID(),
                new BindingSource.WorkflowInput(Pointer.parse("item")))
        def value = new JsonValue.JsonObject([new JsonValue.JsonMember("first", new JsonValue.JsonString("Demo Sample first")),
                                             new JsonValue.JsonMember("later", new JsonValue.JsonString("Demo Sample later"))])
        def single = new StepInputs.ReadRecord(read, value, null, [], [])
        def composite = new StepInputs.ReadRecord(read, value, null, [],
                [new InputRecord.Part(Pointer.parse("later").names(), null),
                 new InputRecord.Part(Pointer.parse("first").names(), null)])

        when:
        def origin = details.origin(child, single)
        def components = details.parts(child, child.run().workflow().takes(), Pointer.parse("item"), composite)

        then:
        origin.kind() == "run_input"
        origin.path() == "ticket"
        origin.run() == new RunReadDetails.NumberedRunRecord(new RunId(UUID.fromString(rootRun)), 71)
        components*.targetPath() == ["later", "first"]
        components*.from()*.path() == ["ticket.later", "ticket.first"]
        components*.from()*.run() == [origin.run()] * 2
        components.every { it.from().maker() == null }

        when:
        details.origin(child, composite)

        then:
        thrown(IllegalArgumentException)
    }

    def "a real read source rejects an unknown maker and an undeclared component"() {
        given:
        def child = context()
        def details = new RunReadDetails(store.session, child)
        def read = new SearchQuestionPart.Read(UUID.randomUUID(),
                new BindingSource.WorkflowInput(Pointer.parse("item")))
        def unknown = new ProductionValueId(UUID.randomUUID())
        def value = new JsonValue.JsonString("Demo Sample")

        when:
        details.origin(child, new StepInputs.ReadRecord(read, value, unknown, [unknown], []))

        then:
        thrown(IllegalStateException)

        when:
        details.parts(child, child.run().workflow().takes(), Pointer.parse("item"),
                new StepInputs.ReadRecord(read, value, null, [],
                        [new InputRecord.Part(Pointer.parse("missing").names(), null)]))

        then:
        thrown(IllegalStateException)
    }

}
