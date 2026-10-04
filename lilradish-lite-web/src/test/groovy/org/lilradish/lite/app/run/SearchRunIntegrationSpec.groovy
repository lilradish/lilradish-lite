package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.inference.AnswerReader
import org.lilradish.lite.app.inference.SearchObservation
import org.lilradish.lite.app.inference.ToolCallOutcome
import org.lilradish.lite.app.inference.ToolCallRequest
import org.lilradish.lite.app.inference.ToolModelCalls
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.KeptAnswer
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.DeployedTool
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.ModelToolKind
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.model.ToolPrice
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.StepAct
import org.lilradish.lite.domain.run.StepGround
import org.lilradish.lite.domain.run.StepInputs
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SearchRunIntegrationSpec extends Specification {

    static final String GROUP = '56550000-0000-4000-8000-000000000001'
    static final String WORKFLOW = '56550000-0000-4000-8000-000000000002'
    static final String VERSION = '56550000-0000-4000-8000-000000000003'
    static final String STEP = '56550000-0000-4000-8000-000000000004'
    static final String RUN = '56550000-0000-4000-8000-000000000005'
    static final String PART = '56550000-0000-4000-8000-000000000006'
    static final String FOLLOWING = '56550000-0000-4000-8000-000000000007'
    static final String FOLLOWING_PART = '56550000-0000-4000-8000-000000000008'
    static final String QUESTION = 'Demo Sample "question"\n'
    static final String ANSWER = '{"title":"Demo Sample title","body":"Demo Sample body","references":"Demo Sample references","other":"Demo Sample other"}'
    static final ModelToolName TOOL = DeployedModels.WEB.requiredTool()

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store
    RunTree tree
    RunSnapshots snapshots
    EngineWrites writes
    EngineExecutor executor
    EngineTaskBarrier tasks
    RunEngine engine
    EngineCalls calls
    StepActs acts
    Closure<ToolCallOutcome> toolAnswer
    Closure<CallOutcome> ordinaryAnswer
    List<CallRequest> ordinaryRequested = [].asSynchronized()
    ToolCallOutcome returned
    List<ToolCallRequest> requested = [].asSynchronized()
    List<List<Boolean>> madeOn = [].asSynchronized()
    List<List<String>> heldAtSending = [].asSynchronized()

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, 'search_run_' + (++databasesMade))
        store.group(GROUP, 'SEARCH', 'Demo Sample')
        store.member(GROUP, LibraryStore.FIRST_STEWARD, 'operator')
        StepRows.workflow(store, WORKFLOW, VERSION, GROUP, 'Demo Sample')
        searching(STEP, 0, 'search_demo')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (search_question_part_id, entry_version_id,
                    workflow_step_id, position, literal_text, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 0, ?, ?::uuid)
                ''').params(PART, VERSION, STEP, QUESTION, LibraryStore.SEEDER).update()
        returned = new ToolCallOutcome(new CallOutcome.CameBack(ANSWER, 10, 20, true, false, null, null),
                [(TOOL): 2L], [new SearchObservation(TOOL, 'Demo Sample sought', 'Demo Sample found')])
        holding()
    }

    def cleanup() {
        executor.destroy()
    }

    private void searching(String step, int position, String name) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                    search_way, producer, producer_model, producer_mode, tries, search_title_standing,
                    search_body_standing, search_references_standing, search_other_standing, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'search', 'web', 'model', 'general', 'ordinary', 1,
                    'always', 'always', 'always', 'always', ?::uuid)
                ''').params(step, VERSION, position, name, LibraryStore.SEEDER).update()
    }

    private void holding(long takes = 100_000, boolean withTool = true, BigDecimal price = new BigDecimal('0.0100'),
                         CodeSteps held = CodeStepsHeld.NONE, List<SearchWay> ways = [DeployedModels.WEB]) {
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, held, new SearchWays(ways))
        writes = new EngineWrites(store.session)
        executor = EngineExecutors.of(store.database)
        tasks = EngineTaskBarrier.attach(executor)
        def deployed = new DeployedModel(new ModelName('general'), [], takes, 4.0G, 4000, [],
                withTool ? [new DeployedTool(TOOL, ModelToolKind.WEB_SEARCH, 5,
                        [new ToolPrice(Currency.getInstance('USD'), price)])] : [])
        def catalog = new ModelCatalog([deployed])
        def ceilings = new CeilingReach(store.session, catalog, writes)
        def tools = { request, progress ->
            requested << request
            madeOn << [Thread.currentThread() instanceof EngineThread,
                       TransactionSynchronizationManager.isActualTransactionActive()]
            heldAtSending << store.texts('''
                    select tool || ' ' || most_uses || ' ' || (uses is null) || ' ' || (model_call_outcome is null)
                      from model_call_tool_uses order by model_call_id
                    ''')
            toolAnswer == null ? returned : toolAnswer.call(request)
        } as ToolModelCalls
        def ordinary = { request, progress ->
            ordinaryRequested << request
            if (ordinaryAnswer == null) {
                throw new AssertionError('This workflow sends a tool call')
            }
            ordinaryAnswer.call(request)
        } as ModelCalls
        def files = EngineWiring.files(store)
        calls = new EngineCalls(tools, ordinary, store.transactions(), tree, writes, ceilings, snapshots, files)
        def codes = new EngineCodes(new CodeRuns(held), store.transactions(), tree, snapshots,
                writes, executor, files)
        engine = new RunEngine(store.session, store.transactions(), tree, snapshots, writes, executor, calls,
                codes, catalog, ceilings, new GroupRoles(store.session), Deployments.DEEP_ENOUGH, files)
        acts = new StepActs(store.session, store.transactions(), new GroupRoles(store.session), tree, snapshots,
                writes, engine, files, EngineWiring.fileDeployment())
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private boolean done(int expected = 1) {
        def context = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def positions = StepPositions.of(context.run())
        positions.size() == expected && positions.every { it instanceof StepPosition.Done }
    }

    private void started() {
        startWithoutWaiting()
        drained()
    }

    private void startWithoutWaiting() {
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, LibraryStore.FIRST_STEWARD, '{}')
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
    }

    private static WorkflowStepId stepId() {
        new WorkflowStepId(UUID.fromString(STEP))
    }

    private UserId caller() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        new UserId(store.session.sql('select user_id from subjects where subject_id = ?::uuid')
                .params(LibraryStore.FIRST_STEWARD).query(String).single())
    }

    private void drained() {
        tasks.awaitDrained()
    }

    def "a model search sends the stored literal once and commits four outputs counts prices and ordered searches"() {
        given:
        returned = new ToolCallOutcome(new CallOutcome.CameBack(ANSWER, 10, 20, true, false, null, null),
                [(TOOL): reported], [new SearchObservation(TOOL, 'Demo Sample sought', 'Demo Sample found'),
                                    new SearchObservation(new ModelToolName('other_search'), 'not requested', 'not kept'),
                                    new SearchObservation(TOOL, '', null)])

        when:
        started()

        then:
        requested.size() == 1
        madeOn == [[true, false]]
        heldAtSending == [['web_search 5 true true']]
        requested.first().call().purpose() == ModelCallPurpose.PRODUCE
        requested.first().tools()*.tool() == [TOOL]
        requested.first().tools()*.maximum() == [5L]
        store.texts('select search_question from run_step_send_attempts') == [QUESTION]
        AnswerReader.read(requested.first().call().sent().user()).members().find { it.name() == 'question' }.value() ==
                new JsonValue.JsonString(QUESTION)
        store.count('select count(*) from production_inputs') == 0
        store.texts('select field_name || \' \' || field_standing || \' \' || needs_review from production_values order by field_name') ==
                ['body always false', 'other always false', 'references always false', 'title always false']
        store.count('select count(*) from production_values where confidence is not null') == 0
        store.count('select count(*) from productions where yielded') == 1
        done()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select outcome::text from model_calls') == ['came_back']
        store.texts('select model_call_outcome || \' \' || uses || \' \' || uses_said from model_call_tool_uses') ==
                ['came_back ' + reported + ' true']
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select currency || \' \' || per_use::text from model_call_tool_prices') == ['USD 0.0100']
        store.session.sql('select searched_for, found from model_call_searches order by position').query().listOfRows() ==
                [[searched_for: 'Demo Sample sought', found: 'Demo Sample found'], [searched_for: '', found: null]]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select position::text from model_call_searches order by position') == ['1', '2']
        store.count('select count(*) from run_step_failures') == 0

        where:
        reported << [0L, 2L, 7L]
    }

    def "an optional output absent from the answer has a real SQL null row and can be read by the next search"() {
        given:
        def answered = '{"title":"Demo Sample title","body":"Demo Sample body","references":null,"other":""}'
        returned = new ToolCallOutcome(new CallOutcome.CameBack(answered, 10, 20, true, false, null, null), [:], [])
        searching(FOLLOWING, 1, 'search_following')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                    position, literal_text, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 0, 'Demo Sample following ', ?::uuid)
                ''').params(UUID.randomUUID().toString(), VERSION, FOLLOWING, LibraryStore.SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                    position, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, 'references', ?::uuid)
                ''').params(FOLLOWING_PART, VERSION, FOLLOWING, STEP, LibraryStore.SEEDER).update()

        when:
        started()
        def context = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def following = context.run().steps().find { it.planned().id().value().toString() == FOLLOWING }
        def read = following.planned().runs().question().find {
            it instanceof SearchQuestionPart.Read && it.id().toString() == FOLLOWING_PART
        }
        def input = following.tries().first().inputs().first()

        then:
        requested.size() == 2
        store.count('select count(*) from production_values') == 8
        store.count('select count(*) from production_values where value is null') == 4
        store.texts('select search_question from run_step_send_attempts order by created_at') ==
                [QUESTION, 'Demo Sample following ']
        input.binding() == null
        input.searchQuestionPart() == UUID.fromString(FOLLOWING_PART)
        input.source() != null
        StepInputs.took(context, read, input).value() == new JsonValue.JsonNull()
        store.texts('select uses || \' \' || uses_said from model_call_tool_uses order by model_call_id') ==
                ['5 false', '5 false']
        store.count('select count(*) from model_call_searches') == 0
        store.count('select count(*) from run_step_failures') == 0
        done(2)
    }

    def "a search value belongs to the held tree even where another tree has a real yielded search production"() {
        given:
        started()
        def other = new RunId(UUID.randomUUID())
        store.transactions().executeWithoutResult {
            StepRows.run(store, other.value().toString(), GROUP, 2, WORKFLOW, VERSION,
                    LibraryStore.FIRST_STEWARD, '{}')
            engine.planStarted(tree.lock(groupId(GROUP), other).orElseThrow())
        }
        drained()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        def foreign = new ProductionId(store.session.sql('select production_id from productions where run_id = ?::uuid')
                .params(other.value()).query(UUID).single())
        store.transactions().executeWithoutResult {
            tree.lock(groupId(GROUP), other).orElseThrow()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            assert store.session.sql("delete from production_values where production_id = ?::uuid and field_name = 'title'")
                    .params(foreign.value()).update() == 1
        }
        def title = new JsonValue.JsonString('Demo Sample title')

        when:
        store.transactions().executeWithoutResult {
            writes.searchValue(tree.lock(groupId(GROUP), runId()).orElseThrow(), foreign,
                    new FieldName('title'), FieldStanding.ALWAYS, title)
        }

        then:
        thrown(IllegalStateException)
        requested.size() == 2
        store.count('select count(*) from productions where yielded') == 2
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count('select count(*) from production_values where production_id = ?::uuid', foreign.value()) == 3
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count('select count(*) from production_values value join productions production using (production_id) where production.run_id = ?::uuid',
                runId().value()) == 4

        when:
        store.transactions().executeWithoutResult {
            writes.searchValue(tree.lock(groupId(GROUP), other).orElseThrow(), foreign,
                    new FieldName('title'), FieldStanding.ALWAYS, title)
        }

        then:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count('select count(*) from production_values where production_id = ?::uuid', foreign.value()) == 4
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select value::text from production_values where production_id = ?::uuid and field_name = 'title'",
                foreign.value()) == ['"Demo Sample title"']
        store.count('select count(*) from production_values') == 8
        store.count('select count(*) from model_calls') == 2
        done()
    }

    def "a historical read held only for length is sent only after the same try is released"() {
        given:
        def answer = '{"title":"Demo Sample title","body":"' + ('x' * 3900) + '","references":null,"other":null}'
        returned = new ToolCallOutcome(new CallOutcome.CameBack(answer, 10, 20, true, false, null, null), [:], [])
        searching(FOLLOWING, 1, 'search_following')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                    position, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 0, ?::uuid, 'body', ?::uuid)
                ''').params(FOLLOWING_PART, VERSION, FOLLOWING, STEP, LibraryStore.SEEDER).update()
        executor.destroy()
        holding(1000)
        started()
        def held = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def source = held.run().steps()[0].newest().orElseThrow().values().find { it.field() == 'body' }.id()
        def pending = held.run().steps()[1].newest().orElseThrow().id()

        expect:
        requested.size() == 1
        store.count("select count(*) from run_step_holds where reason = 'too_long' and released_at is null") == 1
        StepPositions.of(held.run(), held.run().steps()[1]) instanceof StepPosition.HeldBack

        when:
        executor.destroy()
        holding()
        acts.trySending(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(FOLLOWING)), caller())
        drained()
        def after = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        requested.size() == 2
        after.run().steps()[1].newest().orElseThrow().id() == pending
        after.run().steps()[1].newest().orElseThrow().inputs().first().source() == source
        store.texts('select search_question from run_step_send_attempts order by created_at') ==
                [QUESTION, 'x' * 3900, 'x' * 3900]
        done(2)
    }

    def "a search keeps its historical try while two missing code releases recover one source at a time"() {
        given:
        def other = UUID.randomUUID().toString()
        def otherPart = UUID.randomUUID().toString()
        def receipt = new Field(new FieldName('receipt'), null, null, new FieldShape.Text(1000), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def first = new ScriptedCodeStep('send_reply', [], [receipt], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', new JsonValue.JsonString('Demo Sample A'))]) })
        def second = new ScriptedCodeStep('send_ticket', [], [receipt], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', new JsonValue.JsonString('Demo Sample B'))]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_ticket'").update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set position = 2 where workflow_step_id = ?::uuid').params(STEP).update()
        StepRows.codeStep(store, FOLLOWING, VERSION, 0, 'first_code', 'send_reply', 'code', 1)
        StepRows.codeStep(store, other, VERSION, 1, 'second_code', 'send_ticket', 'code', 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                update search_question_parts set literal_text = null, source_step_id = ?::uuid,
                    source_path = 'receipt' where search_question_part_id = ?::uuid
                ''').params(FOLLOWING, PART).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                    position, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, 'receipt', ?::uuid)
                ''').params(otherPart, VERSION, STEP, other, LibraryStore.SEEDER).update()
        executor.destroy()
        holding(100_000, false, new BigDecimal('0.0100'), CodeStepsHeld.of(first, second))
        started()
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def historical = before.run().steps()[2].newest().orElseThrow()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def failure = store.texts('select run_step_failure_id::text from run_step_failures').first()
        def sources = historical.inputs()*.source()

        expect:
        first.runs.get() == 1
        second.runs.get() == 1
        sources.size() == 2
        sources.toSet() == before.run().steps().take(2).collect { it.newest().orElseThrow().values().first().id() }.toSet()
        requested.empty
        store.count('select count(*) from run_step_send_attempts') == 0

        when:
        executor.destroy()
        holding()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        def bothMissing = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        bothMissing.run().steps()[2].hold().source().step() == UUID.fromString(FOLLOWING)
        bothMissing.run().steps()[2].newest().orElseThrow().id() == historical.id()
        requested.empty
        store.count('select count(*) from model_calls') == 0
        store.count('select count(*) from productions') == 3

        when:
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(first))
        engine.drive(groupId(GROUP), runId())
        drained()
        def oneMissing = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        oneMissing.run().steps()[2].hold().source().step() == UUID.fromString(other)
        oneMissing.run().steps()[2].newest().orElseThrow().id() == historical.id()
        oneMissing.run().steps()[2].newest().orElseThrow().inputs()*.source() == sources
        store.count('select count(*) from run_step_holds where released_at is null') == 1
        store.count('select count(*) from run_step_holds where released_at is not null') == 1
        requested.empty
        first.runs.get() == 1
        second.runs.get() == 1

        when:
        store.transactions().executeWithoutResult { RunRows.stopped(store, RUN, LibraryStore.FIRST_STEWARD) }
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(first, second))
        engine.drive(groupId(GROUP), runId())
        drained()
        def stopped = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        requested.empty
        stopped.run().steps()[2].hold().source().runStep() == stopped.run().steps()[1].runStep()
        stopped.run().steps()[2].newest().orElseThrow().id() == historical.id()
        stopped.run().steps()[2].newest().orElseThrow().inputs()*.source() == sources
        store.count('select count(*) from run_step_holds where released_at is null') == 1
        store.count('select count(*) from run_step_send_attempts') == 0
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql('update run_stops set opened_again_at = greatest(now(), created_at), opened_again_by = ?::uuid')
                .params(LibraryStore.FIRST_STEWARD).update()
        engine.drive(groupId(GROUP), runId())
        drained()
        def recovered = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        requested.size() == 1
        AnswerReader.read(requested.first().call().sent().user()).members().find { it.name() == 'question' }.value() ==
                new JsonValue.JsonString('Demo Sample ADemo Sample B')
        recovered.run().steps()[2].newest().orElseThrow().id() == historical.id()
        recovered.run().steps()[2].newest().orElseThrow().inputs()*.source() == sources
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select answers_failure_id::text from run_step_send_attempts') == [failure]
        store.count('select count(*) from run_step_holds where released_at is null') == 0
        store.count('select count(*) from productions') == 3
        store.count('select count(*) from model_calls') == 1
        first.runs.get() == 1
        second.runs.get() == 1
        done(3)
    }

    def "a historical code primitive waits for a matching declaration before the same search try sends it"() {
        given:
        def previous = new ScriptedCodeStep('send_reply', [],
                [new Field(new FieldName('receipt'), null, null, new FieldShape.Plain(kind), new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ALWAYS, null))], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', value)]) })
        def changedShape = change == 'file' ? new FieldShape.File([new MediaTypeName('text/plain')], 1000L) :
                new FieldShape.Text(1000)
        def current = new ScriptedCodeStep('send_reply', [],
                change == 'removed' ? [] : [new Field(new FieldName('receipt'), null, null, changedShape, new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ALWAYS, null))], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', new JsonValue.JsonString('Demo Sample'))]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set position = 1 where workflow_step_id = ?::uuid').params(STEP).update()
        StepRows.codeStep(store, FOLLOWING, VERSION, 0, 'code_demo', 'send_reply', 'code', 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                update search_question_parts set literal_text = null, source_step_id = ?::uuid,
                    source_path = 'receipt' where search_question_part_id = ?::uuid
                ''').params(FOLLOWING, PART).update()
        executor.destroy()
        holding(100_000, false, new BigDecimal('0.0100'), CodeStepsHeld.of(previous))
        started()
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def made = before.run().steps()[0].newest().orElseThrow().values().first()
        def pending = before.run().steps()[1].newest().orElseThrow()

        expect:
        previous.runs.get() == 1
        made.value() == value
        pending.inputs().size() == 1
        pending.inputs().first().source() == made.id()
        requested.empty
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select reason::text from run_step_failures') == ['tool_not_offered']
        store.count('select count(*) from run_step_send_attempts') == 0

        when:
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(current))
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        def after = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        requested.empty
        after.run().steps()[1].newest().orElseThrow().id() == pending.id()
        after.run().steps()[1].newest().orElseThrow().number() == null
        after.run().steps()[1].newest().orElseThrow().inputs().first().source() == made.id()
        after.run().steps()[1].failures().last().reason() == RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        after.run().steps()[1].failures().last().source().field() == 'receipt'
        after.run().steps()[1].failures().last().source().maker().runStep() == after.run().steps()[0].runStep()
        def failed = StepGround.of(after.run(), after.run().steps()[1],
                StepPositions.of(after.run(), after.run().steps()[1]), null)
        StepAct.TRY_SENDING.refusal(failed) == null
        StepAct.ANSWER.refusal(failed) == RefusalCode.STEP_MOVED_ON
        StepAct.ASK_AGAIN.refusal(failed) == RefusalCode.STEP_MOVED_ON
        store.count('select count(*) from model_call_tool_uses where uses > 0') == 0
        store.count('select count(*) from model_calls') == 0
        store.count('select count(*) from run_step_send_attempts') == 0

        when:
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(previous))
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        def recovered = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        requested.size() == 1
        current.runs.get() == 0
        previous.runs.get() == 1
        recovered.run().steps()[0].newest().orElseThrow().values().size() == 1
        recovered.run().steps()[0].newest().orElseThrow().values().first().id() == made.id()
        recovered.run().steps()[0].newest().orElseThrow().values().first().value() == value
        recovered.run().steps()[1].newest().orElseThrow().id() == pending.id()
        recovered.run().steps()[1].newest().orElseThrow().inputs().size() == 1
        recovered.run().steps()[1].newest().orElseThrow().inputs().first().source() == made.id()
        store.texts('select search_question from run_step_send_attempts') == [question]
        AnswerReader.read(requested.first().call().sent().user()).members().find { it.name() == 'question' }.value() ==
                new JsonValue.JsonString(question)
        store.count('select count(*) from model_calls') == 1
        store.count('select count(*) from productions') == 2
        done(2)

        where:
        kind             | value                                         | change    || question
        FieldKind.YES_NO | new JsonValue.JsonBoolean(true)               | 'text'    || 'true'
        FieldKind.NUMBER | new JsonValue.JsonNumber(new BigDecimal('13')) | 'text'    || '13'
        FieldKind.YES_NO | new JsonValue.JsonBoolean(true)               | 'removed' || 'true'
        FieldKind.NUMBER | new JsonValue.JsonNumber(new BigDecimal('13')) | 'removed' || '13'
        FieldKind.YES_NO | new JsonValue.JsonBoolean(true)               | 'file'    || 'true'
        FieldKind.NUMBER | new JsonValue.JsonNumber(new BigDecimal('13')) | 'file'    || '13'
    }

    def "a missing or unapproved term list blocks only the code field read by a search while approved and retired ones resolve"() {
        given:
        def note = new Field(new FieldName('note'), null, null, new FieldShape.Text(1000), new HowMany.One(),
                new Demand.Given(true))
        def category = new Field(new FieldName('category'), null, null, new FieldShape.Text(1000), new HowMany.One(),
                new Demand.Given(true))
        def missing = new EntryVersionId(UUID.randomUUID())
        if (listState != 'absent') {
            def entry = UUID.randomUUID().toString()
            store.entry(entry, GROUP, 'reference_list', 'Demo Sample categories')
            if (listState == 'not_yet') {
                store.version(missing.value().toString(), entry, 1, LibraryStore.FIRST_STEWARD)
            } else {
                store.seeded(missing.value().toString(), entry, 1, listState == 'retired')
            }
            store.content(missing.value().toString(), 'reference_list')
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql('''
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, 0, 'Demo Sample category', 'Demo Sample meaning', ?::uuid)
                    ''').params(missing.value(), LibraryStore.SEEDER).update()
        }
        def term = new Field(category.name(), null, null, new FieldShape.Term(missing), new HowMany.One(), category.demand())
        def receipt = new Field(new FieldName('receipt'), null, null, new FieldShape.Nested([note, category]),
                new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def currentReceipt = new Field(receipt.name(), null, null, new FieldShape.Nested([note, term]),
                receipt.howMany(), receipt.demand())
        def value = new JsonValue.JsonObject([
                new JsonValue.JsonMember('note', new JsonValue.JsonString('Demo Sample note')),
                new JsonValue.JsonMember('category', new JsonValue.JsonString('Demo Sample category'))])
        def original = new ScriptedCodeStep('send_reply', [], [receipt], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', value)]) })
        def current = new ScriptedCodeStep('send_reply', [], [currentReceipt], false,
                { new JsonValue.JsonObject([]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set position = 1 where workflow_step_id = ?::uuid').params(STEP).update()
        StepRows.codeStep(store, FOLLOWING, VERSION, 0, 'code_demo', 'send_reply', 'code', 1)
        def path = selected ? 'receipt.category' : 'receipt.note'
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                update search_question_parts set literal_text = null, source_step_id = ?::uuid,
                    source_path = ? where search_question_part_id = ?::uuid
                ''').params(FOLLOWING, path, PART).update()
        executor.destroy()
        holding(100_000, false, new BigDecimal('0.0100'), CodeStepsHeld.of(original))
        started()
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def historical = before.run().steps()[1].newest().orElseThrow()
        def made = before.run().steps()[0].newest().orElseThrow().values().first()

        when:
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(current))
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        def after = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def target = after.run().steps()[1]

        then:
        target.newest().orElseThrow().id() == historical.id()
        target.newest().orElseThrow().inputs().first().source() == made.id()
        original.runs.get() == 1
        current.runs.get() == 0
        requested.size() == (blocked ? 0 : 1)
        store.count('select count(*) from productions') == 2
        if (blocked) {
            assert target.newest().orElseThrow().number() == null
            assert target.failures().last().reason() == RunStepFailureReason.SOURCE_LIST_NOT_HERE
            assert target.failures().last().source() != null
            assert target.failures().last().source().field() == path
            assert target.failures().last().source().maker().runStep() == after.run().steps()[0].runStep()
            def ground = StepGround.of(after.run(), target, StepPositions.of(after.run(), target), null)
            assert StepAct.TRY_SENDING.refusal(ground) == null
            assert StepAct.ANSWER.refusal(ground) == RefusalCode.STEP_MOVED_ON
            assert StepAct.ASK_AGAIN.refusal(ground) == RefusalCode.STEP_MOVED_ON
            assert store.count('select count(*) from run_step_send_attempts') == 0
            assert store.count('select count(*) from model_calls') == 0
            assert RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING
        } else {
            def question = selected ? 'Demo Sample category' : 'Demo Sample note'
            assert store.texts('select search_question from run_step_send_attempts') == [question]
            assert AnswerReader.read(requested.first().call().sent().user()).members().find { it.name() == 'question' }.value() ==
                    new JsonValue.JsonString(question)
            assert store.count('select count(*) from run_step_failures where source_run_step_id is not null') == 0
            assert done(2)
        }

        where:
        selected | listState || blocked
        true     | 'absent'  || true
        false    | 'absent'  || false
        true     | 'not_yet' || true
        false    | 'not_yet' || false
        true     | 'retired' || false
        false    | 'retired' || false
        true     | 'in_service' || false
        false    | 'in_service' || false
    }

    def "an optional historical code null needs no absent term list while given terms and incompatible declarations fail"() {
        given:
        def receipt = new Field(new FieldName('receipt'), null, null, new FieldShape.Text(1000),
                new HowMany.One(), new Demand.Stands(false, FieldStanding.ALWAYS, null))
        def value = withValue ? new JsonValue.JsonString('Demo Sample term') : new JsonValue.JsonNull()
        def original = new ScriptedCodeStep('send_reply', [], [receipt], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', value)]) })
        def currentShape = change == 'file' ? new FieldShape.File([new MediaTypeName('text/plain')], 1000L) :
                change == 'required' ? new FieldShape.Text(1000) :
                        new FieldShape.Term(new EntryVersionId(UUID.randomUUID()))
        def current = new ScriptedCodeStep('send_reply', [], change == 'removed' ? [] : [
                new Field(receipt.name(), null, null, currentShape, receipt.howMany(),
                        new Demand.Stands(change.startsWith('required'), FieldStanding.ALWAYS, null))], false,
                { new JsonValue.JsonObject([]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set position = 1 where workflow_step_id = ?::uuid').params(STEP).update()
        StepRows.codeStep(store, FOLLOWING, VERSION, 0, 'code_demo', 'send_reply', 'code', 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                update search_question_parts set position = 1, literal_text = null, source_step_id = ?::uuid,
                    source_path = 'receipt' where search_question_part_id = ?::uuid
                ''').params(FOLLOWING, PART).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (entry_version_id, workflow_step_id, position, literal_text, created_by)
                values (?::uuid, ?::uuid, 0, 'Demo Sample ', ?::uuid)
                ''').params(VERSION, STEP, LibraryStore.SEEDER).update()
        executor.destroy()
        holding(100_000, false, new BigDecimal('0.0100'), CodeStepsHeld.of(original))
        started()
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def historical = before.run().steps()[1].newest().orElseThrow()
        def made = before.run().steps()[0].newest().orElseThrow().values().first()

        expect:
        historical.number() == null
        historical.inputs().size() == 1
        historical.inputs().first().source() == made.id()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count('select count(*) from production_values where production_value_id = ?::uuid and value is null',
                made.id().value()) == (withValue ? 0 : 1)
        requested.empty

        when:
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(current))
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        def after = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def target = after.run().steps()[1]

        then:
        target.newest().orElseThrow().id() == historical.id()
        target.newest().orElseThrow().inputs().size() == 1
        target.newest().orElseThrow().inputs().first().source() == made.id()
        original.runs.get() == 1
        current.runs.get() == 0
        store.count('select count(*) from productions') == 2
        if (reason == null) {
            assert requested.size() == 1
            assert store.texts('select search_question from run_step_send_attempts') == ['Demo Sample ']
            assert AnswerReader.read(requested.first().call().sent().user()).members().find { it.name() == 'question' }.value() ==
                    new JsonValue.JsonString('Demo Sample ')
            assert store.count('select count(*) from model_calls') == 1
            assert store.count('select count(*) from run_step_failures where source_run_step_id is not null') == 0
            assert done(2)
        } else {
            assert requested.empty
            assert target.failures().last().reason() == reason
            assert target.failures().last().onTry() == historical.id()
            assert target.failures().last().source().field() == 'receipt'
            assert target.failures().last().source().maker().runStep() == after.run().steps()[0].runStep()
            assert target.newest().orElseThrow().number() == null
            assert store.count('select count(*) from run_step_send_attempts') == 0
            assert store.count('select count(*) from model_calls') == 0
            assert RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING
            def ground = StepGround.of(after.run(), target, StepPositions.of(after.run(), target), null)
            assert StepAct.TRY_SENDING.refusal(ground) == null
            assert StepAct.ANSWER.refusal(ground) == RefusalCode.STEP_MOVED_ON
            assert StepAct.ASK_AGAIN.refusal(ground) == RefusalCode.STEP_MOVED_ON
        }

        where:
        withValue | change || reason
        false | 'term' || null
        true | 'term' || RunStepFailureReason.SOURCE_LIST_NOT_HERE
        false | 'required' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        false | 'required_term' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        false | 'removed' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        false | 'file' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
    }

    def "an unstarted pinned workflow keeps the actual whole or nested output demand used by a later search"() {
        given:
        def childEntry = UUID.randomUUID().toString()
        def childVersion = UUID.randomUUID().toString()
        def childStep = UUID.randomUUID().toString()
        def bundle = UUID.randomUUID().toString()
        store.entry(childEntry, GROUP, 'workflow', 'Demo Sample child')
        store.seeded(childVersion, childEntry, 1)
        store.content(childVersion, 'workflow')
        StepRows.field(store, [id: nestedPath ? bundle : UUID.randomUUID().toString(), owner: childVersion,
                              ownerKind: 'workflow', side: 'gives', position: 0,
                              name: nestedPath ? 'bundle' : 'note', kind: nestedPath ? 'fields' : 'text', mustBe: required])
        if (nestedPath) {
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: childVersion, ownerKind: 'workflow',
                                  side: 'gives', parent: bundle, position: 0, name: 'note', mustBe: true])
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_steps set position = 2 where workflow_step_id = ?::uuid').params(STEP).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                    pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, 1, 'unstarted_child', 'entry', ?::uuid, 'workflow', ?::uuid)
                ''').params(childStep, VERSION, childVersion, LibraryStore.SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        StepRows.codeStep(store, FOLLOWING, VERSION, 0, 'code_demo', 'send_reply', 'code', 1)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                update search_question_parts set literal_text = null, source_step_id = ?::uuid,
                    source_path = 'receipt' where search_question_part_id = ?::uuid
                ''').params(FOLLOWING, PART).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (entry_version_id, workflow_step_id, position,
                    source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, 1, ?::uuid, ?, ?::uuid)
                ''').params(VERSION, STEP, childStep, nestedPath ? 'bundle.note' : 'note', LibraryStore.SEEDER).update()
        def code = new ScriptedCodeStep('send_reply', [],
                [new Field(new FieldName('receipt'), null, null, new FieldShape.Text(1000), new HowMany.One(),
                        new Demand.Stands(false, FieldStanding.ALWAYS, null))], false, {
                    store.transactions().executeWithoutResult { RunRows.stopped(store, RUN, LibraryStore.FIRST_STEWARD) }
                    new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', new JsonValue.JsonNull())])
                })
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(code))

        when:
        started()
        def context = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def child = context.run().steps().find { it.planned().id().value().toString() == childStep }
        def at = Pointer.parse(nestedPath ? 'bundle.note' : 'note').reach(child.planned().runs().gives().fields())

        then:
        at.given() == required
        child.beneath() == null
        store.count('select count(*) from runs') == 1
        requested.empty
        code.runs.get() == (required ? 1 : 0)
        store.count('select count(*) from run_stops where opened_again_at is null') == (required ? 1 : 0)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_reason::text from productions where code_error_reason is not null') ==
                (required ? [] : ['gives_otherwise'])
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_read_by_step::text from productions where code_error_reason is not null') ==
                (required ? [] : [STEP])
        store.count('select count(*) from productions where yielded') == (required ? 1 : 0)
        store.count('select count(*) from model_calls') == 0

        where:
        [nestedPath, required] << [[true, false], [true, false]].combinations()
    }

    def "a completed search whose way is absent still supplies its standard required output to a later search"() {
        given:
        def destination = UUID.randomUUID().toString()
        def readPart = UUID.randomUUID().toString()
        def destinationWay = new SearchWay('destination', TOOL, 1000, 4000, 1000, 1000)
        def code = new ScriptedCodeStep('send_reply', [],
                [new Field(new FieldName('receipt'), null, null, new FieldShape.Text(1000), new HowMany.One(),
                        new Demand.Stands(false, FieldStanding.ALWAYS, null))], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', new JsonValue.JsonNull())]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        StepRows.codeStep(store, FOLLOWING, VERSION, 1, 'code_demo', 'send_reply', 'code', 1)
        searching(destination, 2, 'search_destination')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set search_way = 'destination' where workflow_step_id = ?::uuid")
                .params(destination).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (entry_version_id, workflow_step_id, position,
                    source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, 0, ?::uuid, 'receipt', ?::uuid)
                ''').params(VERSION, destination, FOLLOWING, LibraryStore.SEEDER).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (search_question_part_id, entry_version_id, workflow_step_id,
                    position, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 1, ?::uuid, ?, ?::uuid)
                ''').params(readPart, VERSION, destination, STEP, field, LibraryStore.SEEDER).update()
        toolAnswer = { request ->
            store.transactions().executeWithoutResult { RunRows.stopped(store, RUN, LibraryStore.FIRST_STEWARD) }
            returned
        }
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(code), [DeployedModels.WEB, destinationWay])
        started()
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def source = before.run().steps()[0].newest().orElseThrow().values().find { it.field() == field }

        expect:
        requested.size() == 1
        code.runs.get() == 0
        store.count('select count(*) from run_stops where opened_again_at is null') == 1
        source.value() == new JsonValue.JsonString('Demo Sample ' + field)

        when:
        toolAnswer = null
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.of(code), [destinationWay])
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql('update run_stops set opened_again_at = greatest(now(), created_at), opened_again_by = ?::uuid')
                .params(LibraryStore.FIRST_STEWARD).update()
        engine.drive(groupId(GROUP), runId())
        drained()
        def after = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        after.run().steps()[0].planned().runs().released() == null
        after.run().steps()[0].newest().orElseThrow().values().find { it.field() == field }.id() == source.id()
        code.runs.get() == (required ? 1 : 0)
        requested.size() == (required ? 2 : 1)
        store.count('select count(*) from model_calls') == (required ? 2 : 1)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_reason::text from productions where code_error_reason is not null') ==
                (required ? [] : ['gives_otherwise'])
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_read_by_step::text from productions where code_error_reason is not null') ==
                (required ? [] : [destination])
        store.texts('select search_question from run_step_send_attempts order by created_at') ==
                (required ? [QUESTION, 'Demo Sample ' + field] : [QUESTION])
        !required || (after.run().steps()[2].newest().orElseThrow().inputs().find {
            it.searchQuestionPart() == UUID.fromString(readPart)
        }.source() == source.id())
        !required || done(3)

        where:
        field        || required
        'title'      || true
        'body'       || true
        'references' || false
        'other'      || false
    }

    def "a real downstream model receives the web mark from a search answered by a person"() {
        given:
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null").update()
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        StepRows.question(store, entry, version, GROUP, 'Demo Sample downstream')
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: 'question',
                              side: 'takes', position: 0, name: 'evidence', limit: 4000])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: version, ownerKind: 'question',
                              side: 'gives', position: 0, name: 'answer', standing: 'always', mustBe: true])
        StepRows.step(store, FOLLOWING, VERSION, 1, 'model_following', version, 'model', 1)
        StepRows.binding(store, UUID.randomUUID().toString(), VERSION, FOLLOWING, 'evidence', STEP, 'body')
        ordinaryAnswer = { request ->
            new CallOutcome.CameBack('{"values":{"answer":"Demo Sample downstream"},"confidences":{}}', 10, 20, true, false, null, null)
        }
        started()

        when:
        acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(), AnswerReader.read(ANSWER), 'Demo Sample why')
        drained()
        def sent = AnswerReader.read(ordinaryRequested.first().sent().user())

        then:
        requested.empty
        ordinaryRequested.size() == 1
        sent.members().find { it.name() == 'found_on_the_web' }.value() ==
                new JsonValue.JsonArray([new JsonValue.JsonString('evidence')])
        store.count("select count(*) from model_calls where purpose = 'produce'") == 1
        store.count('select count(*) from model_call_tool_uses') == 0
        done(2)
    }

    def "a person answers the same open search with its reason and real optional null rows"() {
        given:
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null").update()
        started()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def opened = store.texts('select production_id::text from productions')

        when:
        acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(),
                AnswerReader.read('{"title":"Demo Sample title","body":"Demo Sample body","references":null,"other":null}'), 'Demo Sample why')
        drained()

        then:
        requested.empty
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select production_id::text from productions') == opened
        store.texts('select producer || \' \' || ended_by_kind || \' \' || explanation from productions') ==
                ['person person Demo Sample why']
        store.count('select count(*) from production_values') == 4
        store.count('select count(*) from production_values where value is null') == 2
        store.count('select count(*) from model_calls') == 0
        store.count('select count(*) from model_call_tool_uses') == 0
        store.count('select count(*) from production_inputs') == 0
        done()
    }

    def "a search answer cannot be reviewed by the person who gave it even with review permission"() {
        given:
        store.member(GROUP, LibraryStore.FIRST_STEWARD, 'overseer')
        store.session.sql("""
                update workflow_steps set producer = 'person', producer_model = null, producer_mode = null,
                                          search_body_standing = 'never'
                """).update()
        started()
        acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(), AnswerReader.read(ANSWER), 'Demo Sample why')
        drained()

        when:
        acts.review(groupId(GROUP), runId(), stepId(), 1, caller(),
                [body: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)])

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.REVIEW_OWN_PRODUCTION
        store.count('select count(*) from reviews') == 0
        requested.empty
    }

    def "a model lacking the search tool is retried on the same production after deployment"() {
        given:
        executor.destroy()
        holding(100_000, false)
        started()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def opened = store.texts('select production_id::text from productions')

        expect:
        opened.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select reason::text from run_step_failures') == ['tool_not_offered']
        requested.empty
        store.count('select count(*) from model_calls') == 0

        when:
        executor.destroy()
        holding()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()

        then:
        requested.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select production_id::text from productions') == opened
        store.texts('select search_question from run_step_send_attempts') == [QUESTION]
        store.count('select count(*) from run_step_send_attempts where answers_failure_id is not null') == 1
        done()
    }

    def "a literal question too long to cut fails until a larger model sends the same production"() {
        given:
        def large = 'Demo Sample ' * 500
        store.session.sql('update search_question_parts set literal_text = ?').params(large).update()
        executor.destroy()
        holding(1000)
        started()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def opened = store.texts('select production_id::text from productions')

        expect:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select reason::text from run_step_failures') == ['uncuttable_length']
        store.count('select count(*) from run_step_send_attempts') == 0
        requested.empty

        when:
        executor.destroy()
        holding()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()

        then:
        requested.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select production_id::text from productions') == opened
        store.texts('select search_question from run_step_send_attempts') == [large]
        done()
    }

    def "restart settles an actual outstanding search once and ignores its later response"() {
        given:
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        toolAnswer = { request ->
            entered.countDown()
            assert release.await(20, TimeUnit.SECONDS)
            returned
        }
        startWithoutWaiting()
        assert entered.await(10, TimeUnit.SECONDS)
        def restart = new EngineRestart(store.session, store.transactions(), tree, writes, calls, engine, executor)

        when:
        restart.afterSingletonsInstantiated()
        restart.afterSingletonsInstantiated()
        release.countDown()
        drained()

        then:
        requested.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select outcome::text from model_calls') == ['nothing_came_back']
        store.texts('select model_call_outcome || \' \' || uses || \' \' || uses_said from model_call_tool_uses') ==
                ['nothing_came_back 5 false']
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select lost_reason::text from productions') == ['nothing_came_back']
        store.count('select count(*) from production_values') == 0
        store.count('select count(*) from model_call_searches') == 0
        store.count('select count(*) from model_call_tool_prices') == 1

        cleanup:
        release.countDown()
    }

    def "a turnaway keeps its tool use uncounted and repeats exactly the held payload on the same production"() {
        given:
        returned = new ToolCallOutcome(new CallOutcome.TurnedAway(new TurnAway('Demo Sample busy', false)),
                [:], [])
        started()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def opened = store.texts('select production_id::text from productions')
        def sent = requested.first().call().sent()

        expect:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select outcome::text from model_calls') == ['turned_away']
        store.count('select count(*) from model_call_tool_uses where uses is null and model_call_outcome is null') == 1
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING
        store.count('select count(*) from productions where try_number is null and ended_at is null') == 1

        when:
        returned = new ToolCallOutcome(new CallOutcome.CameBack(ANSWER, 10, 20, true, false, null, null), [(TOOL): 1L], [])
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()

        then:
        requested.size() == 2
        requested.last().call().sent().user() == sent.user()
        requested.last().call().sent().system() == sent.system()
        requested.last().call().sent().characters() == sent.characters()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select production_id::text from productions') == opened
        store.count('select count(*) from run_step_send_attempts where repeats_attempt_id is not null') == 1
        store.count('select count(*) from run_step_send_attempts where payload is not null') == 1
        store.count('select count(*) from model_call_tool_uses where uses is null and model_call_outcome is null') == 1
        store.count("select count(*) from model_call_tool_uses where uses = 1 and model_call_outcome = 'came_back'") == 1
        store.count('select count(*) from model_call_searches') == 0
        done()
    }

    def "a stopped search sends nothing until the actual run stop is opened again"() {
        given:
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, LibraryStore.FIRST_STEWARD, '{}')
            RunRows.stopped(store, RUN, LibraryStore.FIRST_STEWARD)
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
        drained()

        expect:
        requested.empty
        store.count('select count(*) from model_calls') == 0
        store.count('select count(*) from model_call_tool_uses') == 0

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql('update run_stops set opened_again_at = greatest(now(), created_at), opened_again_by = ?::uuid')
                .params(LibraryStore.FIRST_STEWARD).update()
        engine.drive(groupId(GROUP), runId())
        drained()

        then:
        requested.size() == 1
        store.count('select count(*) from productions') == 1
        done()
    }

    def "model units stop the next search at the ceiling even with a zero search price"() {
        given:
        executor.destroy()
        holding(100_000, true, BigDecimal.ZERO)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update workflow_versions set ceiling = 30 where entry_version_id = ?::uuid')
                .params(VERSION).update()
        searching(FOLLOWING, 1, 'search_following')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into search_question_parts (entry_version_id, workflow_step_id, position, literal_text, created_by)
                values (?::uuid, ?::uuid, 0, 'Demo Sample following', ?::uuid)
                ''').params(VERSION, FOLLOWING, LibraryStore.SEEDER).update()

        when:
        started()

        then:
        requested.size() == 1
        RunBudget.spentAsRead(store.session, runId(), true).spent() == 30
        store.count('select count(*) from run_stops where ceiling_run_id = run_id and opened_again_at is null') == 1
        store.count('select count(*) from model_calls') == 1
        !done(2)
    }

    def "search prices do not change the model units counted against the run ceiling"() {
        given:
        executor.destroy()
        holding(100_000, true, price)
        started()

        expect:
        RunBudget.spentAsRead(store.session, runId(), true) == new RunBudget.Spend(10, 20, false, 0, 1)
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select per_use::text from model_call_tool_prices') == [price.setScale(4).toString()]
        done()

        where:
        price << [new BigDecimal('0.0000'), new BigDecimal('999999.0000')]
    }

    def "a tool call that errors is counted at its limit with no invented search record or output"() {
        given:
        returned = new ToolCallOutcome(new CallOutcome.Errored('Demo Sample unavailable', null), [:], [])

        when:
        started()

        then:
        requested.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select outcome::text from model_calls') == ['errored']
        store.texts('select model_call_outcome || \' \' || uses || \' \' || uses_said from model_call_tool_uses') ==
                ['errored 5 false']
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select lost_reason::text from productions') == ['errored']
        store.count('select count(*) from production_values') == 0
        store.count('select count(*) from model_call_searches') == 0
    }

    def "an unreadable search service response is kept on the errored producing call without search output"() {
        given:
        returned = new ToolCallOutcome(new CallOutcome.Errored(
                'The model service returned a response this system could not read.',
                KeptAnswer.of('Demo Sample search envelope')), [:], [])

        when:
        started()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('''
                select purpose::text || '|' || outcome::text || '|' || unreadable_response || '|' ||
                       unreadable_response_altered || '|' || unreadable_response_truncated || '|' ||
                       (came_back_count is null)
                  from model_calls
                ''') == ['produce|errored|Demo Sample search envelope|false|false|true']
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select lost_reason::text from productions') == ['errored']
        store.count('select count(*) from production_values') == 0
        store.count('select count(*) from model_call_searches') == 0
    }
}
