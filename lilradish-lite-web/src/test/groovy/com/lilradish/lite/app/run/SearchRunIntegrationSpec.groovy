package com.lilradish.lite.app.run

import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.codestep.CodeRuns
import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.inference.AnswerReader
import com.lilradish.lite.app.inference.SearchObservation
import com.lilradish.lite.app.inference.ToolCallOutcome
import com.lilradish.lite.app.inference.ToolCallRequest
import com.lilradish.lite.app.inference.ToolModelCalls
import com.lilradish.lite.app.library.EntryChanges
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.CallRequest
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.KeptAnswer
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.domain.inference.TurnAway
import com.lilradish.lite.domain.inference.UnreadableResponseRecord
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.DeployedTool
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolKind
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.model.ToolPrice
import com.lilradish.lite.domain.registry.EntryId
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunPositions
import com.lilradish.lite.domain.run.RunState
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.StepAct
import com.lilradish.lite.domain.run.StepFailure
import com.lilradish.lite.domain.run.StepGround
import com.lilradish.lite.domain.run.StepInputs
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.TryRecord
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.codestep.ScriptedCodeStep
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RunRows
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.libprunus.core.error.ApiErrorException
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SearchRunIntegrationSpec extends Specification {

    // DB-SPECIFIC: the SQL fixtures and predicates below input/compare PostgreSQL native enum labels.

    static final String GROUP = '56550000-0000-4000-8000-000000000001'
    static final String WORKFLOW = '56550000-0000-4000-8000-000000000002'
    static final String VERSION = '56550000-0000-4000-8000-000000000003'
    static final String STEP = '56550000-0000-4000-8000-000000000004'
    static final String RUN = '56550000-0000-4000-8000-000000000005'
    static final String BINDING = '56550000-0000-4000-8000-000000000006'
    static final String FOLLOWING = '56550000-0000-4000-8000-000000000007'
    static final String FOLLOWING_BINDING = '56550000-0000-4000-8000-000000000008'
    static final String QUESTION = 'Demo Sample "question"\n'
    static final String ANSWER = '{"title":"Demo Sample title","body":"Demo Sample body","references":"Demo Sample references","other":"Demo Sample other"}'
    static final ModelToolName TOOL = DeployedModels.WEB.requiredTool()

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

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
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, 'search_run_' + (++databasesMade), specificationContext.currentFeature.testTags*.value.contains("postgres-schema-changing"))
        store.group(GROUP, 'SEARCH', 'Demo Sample')
        store.member(GROUP, LibraryStore.FIRST_STEWARD, 'operator')
        StepRows.workflow(store, WORKFLOW, VERSION, GROUP, 'Demo Sample')
        searching(STEP, 0, 'search_demo')
        returned = new ToolCallOutcome(new CallOutcome.CameBack(ANSWER, 10, 20, true, false, null, null),
                [(TOOL): 2L], [new SearchObservation(TOOL, 'Demo Sample sought', 'Demo Sample found')])
        holding()
    }

    def cleanup() {
        executor.destroy()
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private void searching(String step, int position, String name, SearchWay way = DeployedModels.WEB) {
        def questionEntry = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        store.entry(questionEntry, GROUP, 'question', 'Fictional source finder ' + name)
        store.seeded(questionVersion, questionEntry, 1)
        store.content(questionVersion, 'question', QUESTION)
        store.session.sql("update question_versions set search_way = ? where entry_version_id = ?::uuid")
                .params(way.name(), questionVersion).update()
        way.outputs().eachWithIndex { output, index ->
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: 'question',
                                   side: 'gives', position: index + 1, name: output.name().value(),
                                   limit: (output.shape() as com.lilradish.lite.domain.declaration.FieldShape.Text).longest(),
                                   mustBe: output.demand().mustBe(), standing: 'never'])
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                    search_way, producer, producer_model, producer_mode, tries, search_title_standing,
                    search_body_standing, search_references_standing, search_other_standing,
                    pinned_version_id, pinned_kind, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'search', ?, 'model', 'general', 'ordinary', 1,
                    'always', 'always', 'always', 'always', ?::uuid, 'question', ?::uuid)
                ''').params(step, VERSION, position, name, way.name(), questionVersion, LibraryStore.SEEDER).update()
    }


    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private String pinnedQuestion(String step) {
        store.texts('select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid', step).first()
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private void taking(String binding, String step, String target, String source, String path,
                        FieldKind kind = FieldKind.TEXT, int limit = 1000, boolean required = true) {
        def question = pinnedQuestion(step)
        def position = store.count("select count(*) from declaration_fields where entry_version_id = ?::uuid and side = 'takes'", question)
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: question, ownerKind: 'question',
                              side: 'takes', position: position, name: target, kind: kind.published(),
                              limit: limit, mustBe: required])
        StepRows.binding(store, binding, VERSION, step, target, source, path)
    }

    private static JsonValue sentMember(ToolCallRequest request, String name) {
        AnswerReader.read(request.call().sent().user()).members().find { it.name() == name }.value()
    }

    // DB-SPECIFIC: The tool-use query uses PostgreSQL || conversions from bigint and boolean to text.
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
                writes, engine, files, EngineWiring.fileDeployment(), catalog)
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

    private void keptSearchCall(TryRecord aTry, String answer, long sent, long cameBack, long uses,
                                List<Map<String, Object>> records) {
        assert aTry.calls().size() == 1
        assert aTry.attempts().size() == 1
        def call = aTry.calls().first()
        assert call.attempt() == aTry.attempts().first().id()
        assert aTry.attempts().first().purpose() == ModelCallPurpose.PRODUCE
        assert aTry.endedAt() != null
        // DB-SPECIFIC: PostgreSQL ::uuid and ::text casts.
        assert store.session.sql('''
                select production.production_id, production.try_number, call.model_call_id,
                       call.production_id as call_production, call.run_step_send_attempt_id,
                       call.purpose::text, call.outcome::text, call.answer,
                       call.sent_count, call.came_back_count, call.counted_by_model,
                       call.answer_altered, call.answer_truncated, call.declined_words,
                       call.declined_words_altered, call.declined_words_truncated,
                       call.error_detail, call.unreadable_response,
                       (call.ended_at is not null) as call_ended
                  from productions production
                  join model_calls call on call.model_call_id = production.model_call_id
                 where production.production_id = ?::uuid
                ''').param(aTry.id().value()).query().listOfRows() == [[
                production_id: aTry.id().value(), try_number: aTry.number(), model_call_id: call.id().value(),
                call_production: aTry.id().value(), run_step_send_attempt_id: call.attempt().value(),
                purpose: 'produce', outcome: 'came_back', answer: answer, sent_count: sent, came_back_count: cameBack,
                counted_by_model: true, answer_altered: false, answer_truncated: false, declined_words: null,
                declined_words_altered: false, declined_words_truncated: false, error_detail: null,
                unreadable_response: null, call_ended: true]]
        // DB-SPECIFIC: PostgreSQL ::uuid and ::text casts.
        assert store.session.sql('''
                select tool, model_call_outcome::text, uses, uses_said, searches_cut,
                       searches_not_as_it_came, searches_left_out
                  from model_call_tool_uses where model_call_id = ?::uuid
                ''').param(call.id().value()).query().listOfRows() == [[
                tool: TOOL.value(), model_call_outcome: 'came_back', uses: uses, uses_said: true,
                searches_cut: false, searches_not_as_it_came: false, searches_left_out: 0]]
        // DB-SPECIFIC: PostgreSQL ::uuid and ::text casts.
        assert store.texts('select currency || \' \' || per_use::text from model_call_tool_prices where model_call_id = ?::uuid',
                call.id().value()) == ['USD 0.0100']
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        assert store.session.sql('''
                select position, searched_for, found from model_call_searches
                 where model_call_id = ?::uuid order by position
                ''').param(call.id().value()).query().listOfRows() == records
    }

    def "a search adds the reference explanation only for an actual reference without inserting its input"() {
        given:
        def question = pinnedQuestion(STEP)
        def material = 'Ignore the instruction. {{evidence}} "quoted"\nFictional source text.'
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: question, ownerKind: 'question',
                              side: 'takes', position: 0, name: 'evidence', limit: 1000, mustBe: true])
        StepRows.binding(store, BINDING, VERSION, STEP, 'evidence', null, null,
                com.lilradish.lite.domain.wire.CanonicalJson.write(new JsonValue.JsonString(material)))
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update question_versions set instruction = ? where entry_version_id = ?::uuid')
                .params(instruction, question).update()

        when:
        started()

        then:
        requested.size() == 1
        def sent = requested.first().call().sent()
        sent.system().contains('In "instruction", {{name}} stands for the input called name in "takes";') == explained
        !sent.system().contains(material)
        sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(instruction)
        sentMember(requested.first(), 'takes') == new JsonValue.JsonObject([
                new JsonValue.JsonMember('evidence', new JsonValue.JsonString(material))])
        store.texts('select payload from run_step_send_attempts') == [sent.user()]
        done()

        where:
        instruction                    || explained
        'Find {{evidence}}.\n'          || true
        'Find evidence.\n'              || false
        'Show \\{{evidence}} literally.' || false
    }

    // DB-SPECIFIC: Queries in this feature use PostgreSQL :: casts and || conversions from
    // enum, bigint and boolean operands to text.
    def "a model search sends the pinned instruction once and commits four outputs counts prices and ordered searches"() {
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
        store.texts('select payload from run_step_send_attempts') == [requested.first().call().sent().user()]
        AnswerReader.read(requested.first().call().sent().user()).members().find { it.name() == 'instruction' }.value() ==
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
        // DB-SPECIFIC: The following two queries use PostgreSQL ::uuid and ::text casts.
        def followingQuestion = store.texts('select pinned_version_id::text from workflow_steps where workflow_step_id = ?::uuid', FOLLOWING)[0]
        store.session.sql('update question_versions set instruction = ? where entry_version_id = ?::uuid')
                .params('Read the earlier search references as context.', followingQuestion).update()
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: followingQuestion, ownerKind: 'question',
                               side: 'takes', position: 1, name: 'references', limit: 2000, mustBe: false])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('''
                insert into bindings (binding_id, entry_version_id, workflow_step_id,
                    target_path, source_step_id, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'references', ?::uuid, 'references', ?::uuid)
                ''').params(FOLLOWING_BINDING, VERSION, FOLLOWING, STEP, LibraryStore.SEEDER).update()

        when:
        started()
        def context = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def following = context.run().steps().find { it.planned().id().value().toString() == FOLLOWING }
        def read = following.planned().bindings().find { it.id().toString() == FOLLOWING_BINDING }
        def input = following.tries().first().inputs().first()

        then:
        requested.size() == 2
        store.count('select count(*) from production_values') == 8
        store.count('select count(*) from production_values where value is null') == 4
        store.texts('select payload from run_step_send_attempts order by created_at') ==
                requested.collect { it.call().sent().user() }
        AnswerReader.read(requested.last().call().sent().user()).members().find { it.name() == 'takes' }.value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember('references', new JsonValue.JsonNull())])
        input.binding() == UUID.fromString(FOLLOWING_BINDING)
        input.source() != null
        StepInputs.took(context, read, input).value() == new JsonValue.JsonNull()
        // DB-SPECIFIC: PostgreSQL || converts bigint and boolean operands to text.
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
        taking(FOLLOWING_BINDING, FOLLOWING, 'evidence', STEP, 'body', FieldKind.TEXT, 4000)
        executor.destroy()
        holding(1000)
        started()
        def held = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def source = held.run().steps()[0].newest().orElseThrow().values().find { it.field() == 'body' }.id()
        def pending = held.run().steps()[1].newest().orElseThrow().id()

        expect:
        requested.size() == 1
        // DB-SPECIFIC: PostgreSQL native run_step_hold_reason enum literal.
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
        store.texts('select payload from run_step_send_attempts order by created_at') ==
                [requested.first().call().sent().user(), requested.last().call().sent().user(), requested.last().call().sent().user()]
        sentMember(requested.last(), 'instruction') == new JsonValue.JsonString(QUESTION)
        sentMember(requested.last(), 'takes') == new JsonValue.JsonObject([
                new JsonValue.JsonMember('evidence', new JsonValue.JsonString('x' * 3900))])
        done(2)
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a search keeps its historical try while two missing code releases recover one source at a time"() {
        given:
        def other = UUID.randomUUID().toString()
        def otherBinding = '56550000-0000-4000-8000-000000000009'
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
        taking(BINDING, STEP, 'first_evidence', FOLLOWING, 'receipt')
        taking(otherBinding, STEP, 'second_evidence', other, 'receipt')
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
        sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(QUESTION)
        sentMember(requested.first(), 'takes') == AnswerReader.read(
                '{"first_evidence":"Demo Sample A","second_evidence":"Demo Sample B"}')
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

    @spock.lang.Tag("postgres-schema-changing")
    def "a historical code primitive waits for a matching declaration before the same search try sends it"() {
        given:
        def previous = new ScriptedCodeStep('send_reply', [],
                [new Field(new FieldName('receipt'), null, null, new FieldShape.Plain(kind), new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ALWAYS, null))], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', value)]) })
        def changedShape = change == 'file' ? new FieldShape.File([new FileExtension('.txt')], 1000L) :
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
        taking(BINDING, STEP, 'evidence', FOLLOWING, 'receipt', kind)
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
        store.texts('select payload from run_step_send_attempts') == [requested.first().call().sent().user()]
        sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(QUESTION)
        sentMember(requested.first(), 'takes') == new JsonValue.JsonObject([
                new JsonValue.JsonMember('evidence', value)])
        store.count('select count(*) from model_calls') == 1
        store.count('select count(*) from productions') == 2
        done(2)

        where:
        kind             | value                                         | change
        FieldKind.YES_NO | new JsonValue.JsonBoolean(true)               | 'text'
        FieldKind.NUMBER | new JsonValue.JsonNumber(new BigDecimal('13')) | 'text'
        FieldKind.YES_NO | new JsonValue.JsonBoolean(true)               | 'removed'
        FieldKind.NUMBER | new JsonValue.JsonNumber(new BigDecimal('13')) | 'removed'
        FieldKind.YES_NO | new JsonValue.JsonBoolean(true)               | 'file'
        FieldKind.NUMBER | new JsonValue.JsonNumber(new BigDecimal('13')) | 'file'
    }

    @spock.lang.Tag("postgres-schema-changing")
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
        def receipt = new Field(new FieldName('receipt'), null, null, new FieldShape.Nested([note, listState in ['retired', 'in_service'] ? term : category]),
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
        taking(BINDING, STEP, 'evidence', FOLLOWING, path)
        if (selected && listState in ['retired', 'in_service']) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts and native field_kind/declaration_side label inputs and comparisons.
            store.session.sql("""
                    update declaration_fields set kind = 'term', text_limit = null, text_lines = null,
                           term_list_version_id = ?::uuid
                     where entry_version_id = ?::uuid and side = 'takes' and name = 'evidence'
                    """).params(missing.value(), pinnedQuestion(STEP)).update()
        }
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
            def evidence = selected ? 'Demo Sample category' : 'Demo Sample note'
            assert store.texts('select payload from run_step_send_attempts') == [requested.first().call().sent().user()]
            assert sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(QUESTION)
            assert sentMember(requested.first(), 'takes') == new JsonValue.JsonObject([
                    new JsonValue.JsonMember('evidence', new JsonValue.JsonString(evidence))])
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

    @spock.lang.Tag("postgres-schema-changing")
    def "an optional historical code null needs no absent term list while given terms and incompatible declarations fail"() {
        given:
        def receipt = new Field(new FieldName('receipt'), null, null, new FieldShape.Text(1000),
                new HowMany.One(), new Demand.Stands(false, FieldStanding.ALWAYS, null))
        def value = withValue ? new JsonValue.JsonString('Demo Sample term') : new JsonValue.JsonNull()
        def original = new ScriptedCodeStep('send_reply', [], [receipt], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', value)]) })
        def currentShape = change == 'file' ? new FieldShape.File([new FileExtension('.txt')], 1000L) :
                change in ['required', 'unchanged'] ? new FieldShape.Text(1000) :
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
        taking(BINDING, STEP, 'evidence', FOLLOWING, 'receipt', FieldKind.TEXT, 1000, false)

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
            assert store.texts('select payload from run_step_send_attempts') == [requested.first().call().sent().user()]
            assert sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(QUESTION)
            assert sentMember(requested.first(), 'takes') == new JsonValue.JsonObject([
                    new JsonValue.JsonMember('evidence', new JsonValue.JsonNull())])
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
        false | 'term' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        true | 'term' || RunStepFailureReason.SOURCE_LIST_NOT_HERE
        false | 'required' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        false | 'required_term' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        false | 'removed' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        false | 'file' || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        false | 'unchanged' || null
    }

    @spock.lang.Tag("postgres-schema-changing")
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
        taking(BINDING, STEP, 'code_evidence', FOLLOWING, 'receipt', FieldKind.TEXT, 1000, false)
        taking(UUID.randomUUID().toString(), STEP, 'workflow_evidence', childStep,
                nestedPath ? 'bundle.note' : 'note', FieldKind.TEXT, 1000, required)
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
        code.runs.get() == 1
        store.count('select count(*) from run_stops where opened_again_at is null') == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_reason::text from productions where code_error_reason is not null') ==
                []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_read_by_step::text from productions where code_error_reason is not null') ==
                []
        store.count('select count(*) from productions where yielded') == 1
        store.count('select count(*) from model_calls') == 0

        where:
        [nestedPath, required] << [[true, false], [true, false]].combinations()
    }

    @spock.lang.Tag("postgres-schema-changing")
    def "a completed search whose way is absent still supplies its standard required output to a later search"() {
        given:
        def destination = UUID.randomUUID().toString()
        def inputBinding = UUID.randomUUID().toString()
        def destinationWay = new SearchWay('destination', TOOL, 1000, 4000, 1000, 1000)
        def code = new ScriptedCodeStep('send_reply', [],
                [new Field(new FieldName('receipt'), null, null, new FieldShape.Text(1000), new HowMany.One(),
                        new Demand.Stands(false, FieldStanding.ALWAYS, null))], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', new JsonValue.JsonNull())]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        StepRows.codeStep(store, FOLLOWING, VERSION, 1, 'code_demo', 'send_reply', 'code', 1)
        searching(destination, 2, 'search_destination', destinationWay)
        taking(UUID.randomUUID().toString(), destination, 'code_evidence', FOLLOWING, 'receipt',
                FieldKind.TEXT, 1000, false)
        taking(inputBinding, destination, 'search_evidence', STEP, field,
                FieldKind.TEXT, ((FieldShape.Text) Field.named(DeployedModels.WEB.outputs(), new FieldName(field)).shape()).longest(), required)
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
        Pointer.parse(field).reach(before.run().steps()[0].planned().runs().question().gives().fields()).given() == required

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
        code.runs.get() == 1
        requested.size() == 2
        store.count('select count(*) from model_calls') == 2
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_reason::text from productions where code_error_reason is not null') ==
                []
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select code_error_read_by_step::text from productions where code_error_reason is not null') ==
                []
        store.texts('select payload from run_step_send_attempts order by created_at') == requested.collect { it.call().sent().user() }
        sentMember(requested.last(), 'instruction') == new JsonValue.JsonString(QUESTION)
        sentMember(requested.last(), 'takes') == new JsonValue.JsonObject([
                new JsonValue.JsonMember('code_evidence', new JsonValue.JsonNull()),
                new JsonValue.JsonMember('search_evidence', new JsonValue.JsonString('Demo Sample ' + field))])
        after.run().steps()[2].newest().orElseThrow().inputs().find {
            it.binding() == UUID.fromString(inputBinding)
        }.source() == source.id()
        done(3)

        where:
        field        || required
        'title'      || true
        'body'       || true
        'references' || false
        'other'      || false
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL native step_producer and model_call_purpose
    // enum literals.
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

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts, a native step_producer enum literal
    // and || conversions from enum operands to text.
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

    // DB-SPECIFIC: PostgreSQL native step_producer and field_standing enum literals.
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
        store.texts('select payload from run_step_send_attempts') == [requested.first().call().sent().user()]
        sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(QUESTION)
        store.count('select count(*) from run_step_send_attempts where answers_failure_id is not null') == 1
        done()
    }

    def "a pinned instruction too long to cut fails until a larger model sends the same production"() {
        given:
        def large = 'Demo Sample ' * 500
        // DB-SPECIFIC: PostgreSQL ::uuid cast.
        store.session.sql('update question_versions set instruction = ? where entry_version_id = ?::uuid')
                .params(large, pinnedQuestion(STEP)).update()
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
        store.texts('select payload from run_step_send_attempts') == [requested.first().call().sent().user()]
        sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(large)
        done()
    }

    // DB-SPECIFIC: Queries in this feature use PostgreSQL :: casts and || conversions from
    // enum, bigint and boolean operands to text.
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

    def "history reads the current version names while the real model request never carries them"() {
        given:
        def question = pinnedQuestion(STEP)
        def workflowName = 'Workflow label sentinel 7a4f'
        def questionName = 'Question label sentinel 8b5e'
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update entry_versions set version_name = ? where entry_version_id = ?::uuid')
                .params(workflowName, VERSION).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update entry_versions set version_name = ? where entry_version_id = ?::uuid')
                .params(questionName, question).update()
        def roles = new GroupRoles(store.session)
        def runs = new Runs(store.session, roles, store.transactionManager(), snapshots)
        def currencies = new com.lilradish.lite.app.currency.GroupCurrencies(store.session,
                new org.springframework.transaction.support.TransactionTemplate(store.transactionManager()),
                roles, DeployedModels.HELD)
        def readSteps = new RunSteps(store.session, roles, snapshots, store.transactionManager(),
                Deployments.DEEP_ENOUGH, currencies, DeployedModels.HELD)

        when:
        started()

        then:
        requested.size() == 1
        done()
        runs.run(groupId(GROUP), runId(), caller()).workflow().versionName().value() == workflowName
        readSteps.steps(groupId(GROUP), runId(), caller()).steps()[0].runs().versionName() == questionName
        !requested.first().call().sent().user().contains(workflowName)
        !requested.first().call().sent().system().contains(workflowName)
        !requested.first().call().sent().user().contains(questionName)
        !requested.first().call().sent().system().contains(questionName)
        sentMember(requested.first(), 'instruction') == new JsonValue.JsonString(QUESTION)

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update entry_versions set version_name = ? where entry_version_id = ?::uuid')
                .params('Renamed workflow 9c6d', VERSION).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update entry_versions set version_name = ? where entry_version_id = ?::uuid')
                .params('Renamed question 0d7c', question).update()

        then:
        runs.run(groupId(GROUP), runId(), caller()).workflow().versionName().value() == 'Renamed workflow 9c6d'
        readSteps.steps(groupId(GROUP), runId(), caller()).steps()[0].runs().versionName() == 'Renamed question 0d7c'
        requested.size() == 1
        !requested.first().call().sent().user().contains('Renamed workflow 9c6d')
        !requested.first().call().sent().system().contains('Renamed question 0d7c')

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('update entry_versions set version_name = null where entry_version_id in (?::uuid, ?::uuid)')
                .params(VERSION, question).update()

        then:
        runs.run(groupId(GROUP), runId(), caller()).workflow().versionName() == null
        readSteps.steps(groupId(GROUP), runId(), caller()).steps()[0].runs().versionName() == null
        requested.size() == 1
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
        // DB-SPECIFIC: PostgreSQL native model_call_outcome enum literal.
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
        // DB-SPECIFIC: PostgreSQL ::uuid cast.
        store.session.sql('update question_versions set instruction = ? where entry_version_id = ?::uuid')
                .params('Find the next fictional shipment evidence.', pinnedQuestion(FOLLOWING)).update()

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
        store.count('select count(*) from run_stops') == 0
        store.count('select count(*) from model_calls') == 1
        requested.size() == 1
        done()

        where:
        price << [new BigDecimal('0.0000'), new BigDecimal('999999.0000')]
    }

    // DB-SPECIFIC: Queries in this feature use PostgreSQL :: casts and || conversions from
    // enum, bigint and boolean operands to text.
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
                new UnreadableResponseRecord(KeptAnswer.of('Demo Sample search envelope'), stopped)), [:], [])

        when:
        started()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts boolean operands to text.
        store.texts('''
                select purpose::text || '|' || outcome::text || '|' || unreadable_response || '|' ||
                       unreadable_response_altered || '|' || unreadable_response_truncated || '|' ||
                       (came_back_count is null)
                  from model_calls
                ''') == ['produce|errored|Demo Sample search envelope|false|false|true']
        store.texts('select unreadable_response_stopped::text from model_calls') == [stopped.toString()]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select lost_reason::text from productions') == ['errored']
        store.count('select count(*) from production_values') == 0
        store.count('select count(*) from model_call_searches') == 0

        where:
        stopped << [false, true]
    }

    def "a returned search that #shape spends its try and retains evidence counts prices and records without yielding"() {
        given:
        def words = provider == DidNotFitReason.MODEL_DECLINED
                ? '## Imaginary invoice evidence\nAsk Mira Cloudmere for the payment record.' : null
        returned = new ToolCallOutcome(new CallOutcome.CameBack(answer, 10, 20, true, cut, provider, words),
                [(TOOL): 2L], [new SearchObservation(TOOL, 'Imaginary invoice query', 'Imaginary ledger note'),
                              new SearchObservation(new ModelToolName('other_search'), 'Not requested', 'Not retained'),
                              new SearchObservation(TOOL, '', 'Imaginary second source')])
        def kept = answer.length() > 8_388_608 ? answer.substring(0, 8_388_608) : answer
        def digest = java.security.MessageDigest.getInstance('MD5').digest(kept.getBytes('UTF-8')).encodeHex().toString()

        when:
        started()

        then:
        requested.size() == 1
        requested.first().call().purpose() == ModelCallPurpose.PRODUCE
        ordinaryRequested.empty
        madeOn == [[true, false]]
        // DB-SPECIFIC: PostgreSQL ::text casts and md5(text).
        store.texts('select outcome::text from model_calls') == ['came_back']
        store.texts('select md5(answer) from model_calls') == [digest]
        store.session.sql('select answer_altered, answer_truncated, declined_words, declined_words_altered, declined_words_truncated from model_calls')
                .query().listOfRows() == [[answer_altered: truncated, answer_truncated: truncated,
                                          declined_words: words, declined_words_altered: false, declined_words_truncated: false]]
        // DB-SPECIFIC: PostgreSQL ::text casts; || converts integer operands to text.
        store.texts('select lost_reason::text || \' \' || did_not_fit_reason::text || \' \' || try_number from productions') ==
                ['did_not_fit ' + reason + ' 1']
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from productions where yielded') == 0
        store.count('select count(*) from production_values') == 0
        store.count('select count(*) from reviews') == 0
        store.count('select count(*) from run_step_send_attempts') == 1
        // DB-SPECIFIC: PostgreSQL || converts enum, bigint and boolean operands to text.
        store.texts('select model_call_outcome || \' \' || uses || \' \' || uses_said from model_call_tool_uses') ==
                ['came_back 2 true']
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select currency || \' \' || per_use::text from model_call_tool_prices') == ['USD 0.0100']
        store.session.sql('select searched_for, found from model_call_searches order by position').query().listOfRows() ==
                [[searched_for: 'Imaginary invoice query', found: 'Imaginary ledger note'],
                 [searched_for: '', found: 'Imaginary second source']]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select position::text from model_call_searches order by position') == ['1', '2']
        !done()
        def context = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def positions = StepPositions.of(context.run())
        positions.size() == 1
        positions.first() instanceof StepPosition.Failed
        positions.first().why() == new StepFailure.TriesSpent(1, 1)
        RunPositions.state(context.run(), positions) == RunState.FAILED
        context.run().steps().size() == 1
        context.run().steps().first().tries().size() == 1
        def aTry = context.run().steps().first().tries().first()
        aTry.number() == 1
        aTry.endedAt() != null
        aTry.calls().size() == 1
        aTry.attempts().size() == 1

        where:
        shape | answer | cut | provider | reason | truncated
        'declines a fitting answer' | ANSWER | false | DidNotFitReason.MODEL_DECLINED | 'model_declined' | false
        'declines despite a cutoff' | ANSWER | true | DidNotFitReason.MODEL_DECLINED | 'model_declined' | false
        'stops at its output limit' | ANSWER | true | null | 'cut_off' | false
        'omits its required body' | '{"title":"Imaginary invoice note","references":null,"other":null}' | false | null | 'field_missing' | false
        'exceeds its body limit' | '{"title":"Imaginary invoice note","body":"' + ('x' * 4001) + '","references":null,"other":null}' | false | null | 'too_long' | false
        'does not return JSON' | 'Imaginary invoice evidence has not arrived.' | false | null | 'not_the_shape' | false
        'exceeds the original-answer bound' | '{"title":"Imaginary invoice note","body":"' + ('x' * 8_388_609) + '","references":null,"other":null}' | false | null | 'too_long' | true
    }

    def "a failed search with tries left automatically starts the next search and keeps each call's evidence"() {
        given:
        store.session.sql('update workflow_steps set tries = 2').update()
        def firstAnswer = 'Imaginary invoice evidence has not arrived.'
        def secondAnswer = '{"title":"Imaginary invoice correction","body":"Keep the imaginary payment record too.","references":"Imaginary ledger and receipt","other":""}'
        toolAnswer = { request ->
            requested.size() == 1
                    ? new ToolCallOutcome(new CallOutcome.CameBack(firstAnswer, 10, 20, true, false, null, null),
                            [(TOOL): 2L], [new SearchObservation(TOOL, 'Imaginary failed query', 'Imaginary first note'),
                                          new SearchObservation(TOOL, 'Imaginary failed followup', 'Imaginary second note')])
                    : new ToolCallOutcome(new CallOutcome.CameBack(secondAnswer, 12, 24, true, false, null, null),
                            [(TOOL): 1L], [new SearchObservation(TOOL, 'Imaginary corrected query', 'Imaginary receipt'),
                                          new SearchObservation(TOOL, 'Imaginary corrected followup', 'Imaginary ledger')])
        }

        when:
        started()
        def context = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def tries = context.run().steps().first().tries()
        def positions = StepPositions.of(context.run())

        then:
        requested.size() == 2
        requested.every { it.call().purpose() == ModelCallPurpose.PRODUCE }
        requested*.call()*.sent()*.user().unique().size() == 1
        ordinaryRequested.empty
        madeOn == [[true, false], [true, false]]
        tries*.number() == [1, 2]
        tries*.id().unique().size() == 2
        tries.collect { it.calls().first().id() }.unique().size() == 2
        tries.first().values().empty
        tries.last().values().collectEntries { [(it.field()): it.value()] } == [
                title: new JsonValue.JsonString('Imaginary invoice correction'),
                body: new JsonValue.JsonString('Keep the imaginary payment record too.'),
                references: new JsonValue.JsonString('Imaginary ledger and receipt'), other: new JsonValue.JsonNull()]
        keptSearchCall(tries.first(), firstAnswer, 10, 20, 2,
                [[position: 1, searched_for: 'Imaginary failed query', found: 'Imaginary first note'],
                 [position: 2, searched_for: 'Imaginary failed followup', found: 'Imaginary second note']])
        keptSearchCall(tries.last(), secondAnswer, 12, 24, 1,
                [[position: 1, searched_for: 'Imaginary corrected query', found: 'Imaginary receipt'],
                 [position: 2, searched_for: 'Imaginary corrected followup', found: 'Imaginary ledger']])
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.session.sql('''
                select try_number, lost_reason::text, did_not_fit_reason::text, yielded
                  from productions order by try_number
                ''').query().listOfRows() == [
                [try_number: 1, lost_reason: 'did_not_fit', did_not_fit_reason: 'not_the_shape', yielded: false],
                [try_number: 2, lost_reason: null, did_not_fit_reason: null, yielded: true]]
        store.count('select count(*) from productions') == 2
        store.count('select count(*) from model_calls') == 2
        store.count('select count(*) from run_step_send_attempts') == 2
        store.count('select count(*) from production_values') == 4
        store.count('select count(*) from reviews') == 0
        store.count('select count(*) from model_call_tool_uses') == 2
        store.count('select count(*) from model_call_tool_prices') == 2
        store.count('select count(*) from model_call_searches') == 4
        positions == [new StepPosition.Done()]
        RunPositions.state(context.run(), positions) == RunState.DONE
    }

    def "a real human refusal is #told to the next search without replacing the first call's evidence"() {
        given:
        store.member(GROUP, LibraryStore.FIRST_STEWARD, 'overseer')
        // DB-SPECIFIC: PostgreSQL native field_standing enum literal.
        store.session.sql("update workflow_steps set search_body_standing = 'never', tries = 2, tells_what_happened = ?")
                .param(told).update()
        def firstAnswer = '{"title":"Imaginary invoice review","body":"Keep the imaginary invoice.","references":"Imaginary paper ledger","other":""}'
        def firstOutput = '{"title":"Imaginary invoice review","body":"Keep the imaginary invoice.","references":"Imaginary paper ledger","other":null}'
        def secondAnswer = '{"title":"Imaginary invoice correction","body":"Keep the imaginary payment record too.","references":"Imaginary ledger and receipt","other":""}'
        def reason = '## Missing evidence\nCompare the imaginary invoice with its payment record.'
        toolAnswer = { request ->
            requested.size() == 1
                    ? new ToolCallOutcome(new CallOutcome.CameBack(firstAnswer, 10, 20, true, false, null, null),
                            [(TOOL): 2L], [new SearchObservation(TOOL, 'Imaginary first query', 'Imaginary first source')])
                    : new ToolCallOutcome(new CallOutcome.CameBack(secondAnswer, 12, 24, true, false, null, null),
                            [(TOOL): 1L], [new SearchObservation(TOOL, 'Imaginary second query', 'Imaginary second source'),
                                          new SearchObservation(TOOL, 'Imaginary second followup', 'Imaginary second ledger')])
        }
        started()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def firstProduction = store.texts('select production_id::text from productions').first()
        def firstCall = store.texts('select model_call_id::text from model_calls').first()

        when:
        acts.review(groupId(GROUP), runId(), stepId(), 1, caller(),
                [body: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, reason)])
        drained()
        acts.askAgain(groupId(GROUP), runId(), stepId(), 2, caller())
        drained()
        def payloads = requested.collect { AnswerReader.read(it.call().sent().user()) }
        def latest = payloads.last()
        def refused = latest.members().find { it.name() == 'refused' }

        then:
        requested.size() == 2
        requested.every { it.call().purpose() == ModelCallPurpose.PRODUCE }
        ordinaryRequested.empty
        payloads.every { it.members().find { member -> member.name() == 'instruction' }.value() == new JsonValue.JsonString(QUESTION) }
        payloads.first().members().every { it.name() != 'refused' }
        if (told) {
            assert refused != null
            assert refused.value().members().find { it.name() == 'values' }.value() == AnswerReader.read(firstOutput)
            assert refused.value().members().find { it.name() == 'words' }.value() ==
                    AnswerReader.read('{"body":"## Missing evidence\\nCompare the imaginary invoice with its payment record."}')
        } else {
            assert refused == null
            assert latest == payloads.first()
        }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select try_number::text from productions order by try_number') == ['1', '2']
        store.count('select count(*) from productions where yielded') == 2
        store.count('select count(*) from reviews') == 1
        store.count('select count(*) from production_values') == 8
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('select answer, sent_count, came_back_count from model_calls where model_call_id = ?::uuid')
                .param(firstCall).query().listOfRows() == [[answer: firstAnswer, sent_count: 10L, came_back_count: 20L]]
        // DB-SPECIFIC: The following three queries use PostgreSQL ::uuid and ::text casts;
        // || converts bigint and boolean operands to text.
        store.texts('select currency || \' \' || per_use::text from model_call_tool_prices where model_call_id = ?::uuid',
                UUID.fromString(firstCall)) == ['USD 0.0100']
        store.texts('select uses || \' \' || uses_said from model_call_tool_uses where model_call_id = ?::uuid',
                UUID.fromString(firstCall)) == ['2 true']
        store.session.sql('select searched_for, found from model_call_searches where model_call_id = ?::uuid order by position')
                .param(firstCall).query().listOfRows() == [[searched_for: 'Imaginary first query', found: 'Imaginary first source']]
        store.count('select count(*) from model_call_tool_prices') == 2
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count('select count(*) from model_calls where production_id = ?::uuid', UUID.fromString(firstProduction)) == 1
        !done()
        def context = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        context.run().steps().size() == 1
        def tries = context.run().steps().first().tries()
        tries*.number() == [1, 2]
        tries.first().id().value() == UUID.fromString(firstProduction)
        tries.first().calls().first().id().value() == UUID.fromString(firstCall)
        tries*.id().unique().size() == 2
        tries.collect { it.calls().first().id() }.unique().size() == 2
        keptSearchCall(tries.first(), firstAnswer, 10, 20, 2,
                [[position: 1, searched_for: 'Imaginary first query', found: 'Imaginary first source']])
        keptSearchCall(tries.last(), secondAnswer, 12, 24, 1,
                [[position: 1, searched_for: 'Imaginary second query', found: 'Imaginary second source'],
                 [position: 2, searched_for: 'Imaginary second followup', found: 'Imaginary second ledger']])
        tries.last().values().collectEntries { [(it.field()): it.value()] } == [
                title: new JsonValue.JsonString('Imaginary invoice correction'),
                body: new JsonValue.JsonString('Keep the imaginary payment record too.'),
                references: new JsonValue.JsonString('Imaginary ledger and receipt'), other: new JsonValue.JsonNull()]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.session.sql('''
                select try_number, lost_reason::text, did_not_fit_reason::text, yielded
                  from productions order by try_number
                ''').query().listOfRows() == [
                [try_number: 1, lost_reason: null, did_not_fit_reason: null, yielded: true],
                [try_number: 2, lost_reason: null, did_not_fit_reason: null, yielded: true]]
        store.count('select count(*) from productions') == 2
        store.count('select count(*) from model_calls') == 2
        store.count('select count(*) from run_step_send_attempts') == 2
        store.count('select count(*) from model_call_tool_uses') == 2
        store.count('select count(*) from model_call_searches') == 3

        when:
        acts.review(groupId(GROUP), runId(), stepId(), 2, caller(),
                [body: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)])
        drained()

        then:
        done()
        requested.size() == 2
        store.count('select count(*) from reviews') == 2
        ordinaryRequested.empty

        where:
        told << [true, false]
    }

    def "an unavailable search tool records its failure at the write boundary without sending"() {
        given:
        StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, LibraryStore.FIRST_STEWARD, "{}")
        java.time.OffsetDateTime boundary

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), new RunId(UUID.fromString(RUN))).orElseThrow()
            def run = snapshots.locked(locked, new RunId(UUID.fromString(RUN)))
            def step = writes.runStep(locked, run, run.steps()[0])
            def aTry = writes.tried(locked, run, step, 1,
                    com.lilradish.lite.domain.workflow.StepProducer.MODEL, null, null, [])
            // DB-SPECIFIC: PostgreSQL clock_timestamp() measures the write after this transaction began.
            boundary = store.session.sql("select clock_timestamp()").query(java.time.OffsetDateTime).single()
            writes.toolNotOffered(locked, run, step, aTry, TOOL)
        }

        then:
        store.session.sql("select created_at from run_step_failures")
                .query(java.time.OffsetDateTime).single() >= boundary
        // DB-SPECIFIC: PostgreSQL native run_step_failure_reason enum literal.
        store.count("select count(*) from run_step_failures where reason = 'tool_not_offered'") == 1
        store.count("select count(*) from productions where ended_at is null") == 1
        store.count("select count(*) from model_calls") == 0
        store.count("select count(*) from production_values") == 0
        requested == []
        ordinaryRequested == []
    }


    private static SearchWay changedWay() {
        def limits = DeployedModels.WEB.outputs().collect { ((FieldShape.Text) it.shape()).longest() }
        new SearchWay('web', TOOL, limits[0] + 1, limits[1], limits[2], limits[3])
    }

    // DB-SPECIFIC: SQL in this method uses PostgreSQL :: casts.
    private Map shapeFailure() {
        store.session.sql("""
                select run_step_failure_id::text as id, production_id, purpose, resolved_at, resolved_by
                  from run_step_failures where reason::text = 'search_question_no_longer_matches'
                """).query().singleRow()
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts and native step_producer and
    // group_role enum literals.
    def "the pinned question stop prevents #producer production #stage and its let-go resumes the actual run"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update group_members set role = 'owner' where group_id = ?::uuid")
                .param(GROUP).update()
        if (producer == 'person') {
            store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null")
                    .update()
        }
        def entry = new EntryId(UUID.fromString(store.texts(
                'select entry_id::text from entry_versions where entry_version_id = ?::uuid', pinnedQuestion(STEP)).first()))
        if (stage == 'with an open unsent try') {
            executor.destroy()
            holding(100_000, false)
            started()
            executor.destroy()
            holding()
        }
        def priorProductions = store.texts('select production_id::text from productions')
        def changes = new EntryChanges(store.session, store.transactions(), new GroupRoles(store.session), engine)
        changes.stop(groupId(GROUP), EntryKind.QUESTION, entry, caller())

        when:
        if (stage == 'before its first try') started()
        else {
            engine.drive(groupId(GROUP), runId())
            drained()
        }
        def stopped = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def beforeActs = store.contents()

        then:
        stopped.run().steps()[0].pinStopped() != null
        stopped.run().stoppedFor(stopped.run().steps()[0]) == stopped.run().steps()[0].pinStopped()
        store.texts('select production_id::text from productions') == priorProductions
        store.count('select count(*) from run_step_send_attempts') == 0
        store.count('select count(*) from model_calls') == 0
        store.count('select count(*) from production_values') == 0
        requested.empty
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING

        when:
        acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(), AnswerReader.read(ANSWER), 'Invented findings checked.')

        then:
        def answer = thrown(ApiErrorException)
        answer.errorCode() == (producer == 'person' && stage == 'with an open unsent try'
                ? RefusalCode.ENTRY_STOPPED : RefusalCode.STEP_MOVED_ON)
        store.contents() == beforeActs

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(), 1, caller())

        then:
        def again = thrown(ApiErrorException)
        again.errorCode() == RefusalCode.STEP_MOVED_ON
        store.contents() == beforeActs

        when:
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())

        then:
        def sending = thrown(ApiErrorException)
        sending.errorCode() == (stage == 'with an open unsent try' && producer == 'model'
                ? RefusalCode.ENTRY_STOPPED : RefusalCode.TRY_SENDING_NOT_OFFERED)
        store.contents() == beforeActs

        when:
        changes.letGo(groupId(GROUP), EntryKind.QUESTION, entry, caller())
        drained()
        if (stage == 'with an open unsent try' && producer == 'model') {
            acts.trySending(groupId(GROUP), runId(), stepId(), caller())
            drained()
        }
        if (producer == 'person') {
            acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(), AnswerReader.read(ANSWER), 'Invented findings checked.')
            drained()
        }

        then:
        done()
        store.count('select count(*) from productions') == 1
        if (!priorProductions.empty) {
            assert store.texts('select production_id::text from productions') == priorProductions
        }
        store.count('select count(*) from production_values') == 4
        requested.size() == (producer == 'model' ? 1 : 0)
        store.count('select count(*) from run_step_send_attempts') == (producer == 'model' ? 1 : 0)
        store.count('select count(*) from entry_stops where let_go_at is not null') == 1

        where:
        producer | stage
        'model'  | 'before its first try'
        'person' | 'before its first try'
        'model'  | 'with an open unsent try'
        'person' | 'with an open unsent try'
    }

    def "two legitimate callers waiting on the real tree lock resolve one search failure and send one try"() {
        given:
        def secondSubject = '56550000-0000-4000-8000-000000000011'
        def secondCaller = new UserId('fictional_second')
        store.person(secondSubject, secondCaller.value(), 'Invented second caller')
        store.member(GROUP, secondSubject, 'overseer')
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])
        started()
        def failure = shapeFailure()
        executor.destroy()
        holding()
        // DB-SPECIFIC: PostgreSQL FOR NO KEY UPDATE; uuid literal.
        def heldRoot = store.holding("select run_id from runs where run_id = '${RUN}' for no key update")
        def racing = Executors.newFixedThreadPool(2)
        def callers = [[caller(), LibraryStore.FIRST_STEWARD], [secondCaller, secondSubject]]
        def attempts = []

        when:
        callers.each { user, subject ->
            attempts << racing.submit({
                try {
                    acts.trySending(groupId(GROUP), runId(), stepId(), user)
                    return [subject, null]
                } catch (ApiErrorException refused) {
                    return [subject, refused.errorCode()]
                }
            } as Callable)
        }
        try {
            store.untilWaiting(2)
        } catch (AssertionError insufficientWaiters) {
            def premature = attempts.collect { attempt -> attempt.done ? attempt.get() : 'still running' }
            throw new AssertionError("Both presses must reach the tree lock: ${premature}", insufficientWaiters)
        }
        heldRoot.commit()
        heldRoot.close()
        def outcomes = attempts.collect { it.get(10, TimeUnit.SECONDS) }
        drained()
        def resolved = shapeFailure()

        then:
        outcomes.count { it[1] == null } == 1
        outcomes.findAll { it[1] != null }*.getAt(1).every {
            it == RefusalCode.STEP_MOVED_ON || it == RefusalCode.TRY_SENDING_NOT_OFFERED
        }
        resolved.id == failure.id
        resolved.resolved_at != null
        resolved.resolved_by == UUID.fromString(outcomes.find { it[1] == null }[0] as String)
        store.count('select count(*) from run_step_failures') == 1
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from productions where try_number = 1') == 1
        store.count('select count(*) from run_step_send_attempts') == 1
        store.count('select count(*) from run_step_send_attempts where answers_failure_id is not null') == 0
        store.count('select count(*) from model_calls') == 1
        store.count('select count(*) from production_values') == 4
        requested.size() == 1
        done()

        cleanup:
        heldRoot?.close()
        attempts?.each { if (!it.done) it.cancel(true) }
        racing?.shutdownNow()
        assert racing == null || racing.awaitTermination(5, TimeUnit.SECONDS)
    }

    def "a failure during the normal plan rolls back the exact search resolution and every production write"() {
        given:
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])
        started()
        def failure = shapeFailure()
        executor.destroy()
        holding()
        // DB-SPECIFIC: PostgreSQL PL/pgSQL trigger, RAISE EXCEPTION and SQLSTATE P0001.
        store.session.sql('''
                create function fictional_production_fault() returns trigger language plpgsql as $$
                begin
                    raise exception 'Fictional planner failure';
                end
                $$
                ''').update()
        store.session.sql('''
                create trigger fictional_production_fault before insert on productions
                for each row execute function fictional_production_fault()
                ''').update()
        def before = store.contents()

        when:
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())

        then:
        def refused = thrown(org.springframework.dao.DataAccessException)
        refused.rootCause instanceof org.postgresql.util.PSQLException
        refused.rootCause.getSQLState() == 'P0001'
        refused.rootCause.serverErrorMessage.message == 'Fictional planner failure'
        shapeFailure() == failure
        store.contents() == before
        store.count('select count(*) from productions') == 0
        store.count('select count(*) from run_step_send_attempts') == 0
        store.count('select count(*) from model_calls') == 0
        requested.empty
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING

        when:
        store.session.sql('drop trigger fictional_production_fault on productions').update()
        store.session.sql('drop function fictional_production_fault()').update()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()

        then:
        shapeFailure().id == failure.id
        shapeFailure().resolved_at != null
        shapeFailure().resolved_by == UUID.fromString(LibraryStore.FIRST_STEWARD)
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from run_step_send_attempts') == 1
        store.count('select count(*) from model_calls') == 1
        requested.size() == 1
        done()

        cleanup:
        store.session.sql('drop trigger if exists fictional_production_fault on productions').update()
        store.session.sql('drop function if exists fictional_production_fault()').update()
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts.
    def "another release drift records a new unresolved failure while preserving the previously resolved row"() {
        given:
        store.session.sql('update workflow_steps set tries = 2').update()
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])
        started()
        def first = shapeFailure()
        executor.destroy()
        holding()
        returned = new ToolCallOutcome(new CallOutcome.CameBack('{}', 10, 20, true, false, null, null), [:], [])
        toolAnswer = { request ->
            store.transactions().executeWithoutResult { RunRows.stopped(store, RUN, LibraryStore.FIRST_STEWARD) }
            returned
        }

        when:
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        def resolved = shapeFailure()
        def production = store.texts('select production_id::text from productions').first()
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])
        // DB-SPECIFIC: PostgreSQL now() and ::uuid cast.
        store.session.sql('update run_stops set opened_again_at = greatest(now(), created_at), opened_again_by = ?::uuid')
                .param(LibraryStore.FIRST_STEWARD).update()
        engine.drive(groupId(GROUP), runId())
        drained()

        then:
        resolved.id == first.id
        resolved.resolved_at != null
        store.count("select count(*) from run_step_failures where reason::text = 'search_question_no_longer_matches'") == 2
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from productions where ended_at is not null') == 1
        store.texts('select production_id::text from productions') == [production]
        store.count('select count(*) from run_step_send_attempts') == 1
        store.count('select count(*) from model_calls') == 1
        requested.size() == 1
        def rows = store.session.sql('''
                select run_step_failure_id::text as id, production_id, purpose, resolved_at, resolved_by
                  from run_step_failures where reason::text = 'search_question_no_longer_matches'
                ''').query().listOfRows()
        rows.find { it.id == resolved.id } == resolved
        def second = rows.find { it.id != resolved.id }
        second != null
        second.production_id == null
        second.purpose == null
        second.resolved_at == null
        second.resolved_by == null

        when:
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()

        then:
        store.session.sql('''
                select run_step_failure_id::text as id, production_id, purpose, resolved_at, resolved_by
                  from run_step_failures where reason::text = 'search_question_no_longer_matches'
                ''').query().listOfRows() as Set == rows as Set
        store.texts('select production_id::text from productions') == [production]
        store.count('select count(*) from run_step_send_attempts') == 1
        store.count('select count(*) from model_calls') == 1
        requested.size() == 1
    }

    // DB-SPECIFIC: PostgreSQL native step_producer enum literal.
    def "a changed search shape uses no first #producer try and recovers once from the exact failure"() {
        given:
        if (producer == 'person') {
            store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null")
                    .update()
        }
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])

        when:
        started()
        def failure = shapeFailure()

        then:
        failure.production_id == null
        failure.purpose == null
        failure.resolved_at == null
        failure.resolved_by == null
        store.count('select count(*) from productions') == 0
        store.count('select count(*) from run_step_send_attempts') == 0
        store.count('select count(*) from model_calls') == 0
        requested.empty
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING

        when:
        engine.drive(groupId(GROUP), runId())
        drained()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])
        new EngineRestart(store.session, store.transactions(), tree, writes, calls, engine, executor)
                .afterSingletonsInstantiated()
        drained()

        then:
        shapeFailure() == failure
        store.count('select count(*) from run_step_failures') == 1
        store.count('select count(*) from productions') == 0
        store.count('select count(*) from run_step_send_attempts') == 0
        store.count('select count(*) from model_calls') == 0
        requested.empty

        when:
        executor.destroy()
        holding()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        def resolved = shapeFailure()

        then:
        resolved.id == failure.id
        resolved.production_id == null
        resolved.purpose == null
        resolved.resolved_at != null
        resolved.resolved_by == UUID.fromString(LibraryStore.FIRST_STEWARD)
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from productions where try_number = 1') == 1
        store.count('select count(*) from run_step_send_attempts where answers_failure_id is not null') == 0
        requested.size() == (producer == 'model' ? 1 : 0)

        when:
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())

        then:
        def stale = thrown(ApiErrorException)
        stale.errorCode() == RefusalCode.STEP_MOVED_ON
        shapeFailure() == resolved
        store.count('select count(*) from productions') == 1
        requested.size() == (producer == 'model' ? 1 : 0)

        when:
        if (producer == 'person') {
            acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(), AnswerReader.read(ANSWER), 'Fictional findings checked.')
            drained()
        }
        engine.drive(groupId(GROUP), runId())
        drained()

        then:
        done()
        store.count('select count(*) from run_step_failures') == 1
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from run_step_send_attempts') == (producer == 'model' ? 1 : 0)
        store.count('select count(*) from model_calls') == (producer == 'model' ? 1 : 0)
        store.count('select count(*) from production_values') == 4

        where:
        producer << ['model', 'person']
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts and a native step_producer enum literal.
    def "an open unsent #producer production keeps its identity when release drift refuses an act durably"() {
        given:
        if (producer == 'person') {
            store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null")
                    .update()
        }
        executor.destroy()
        holding(100_000, false)
        started()
        def opened = store.texts('select production_id::text from productions').first()
        def oldFailures = store.texts('select run_step_failure_id::text from run_step_failures order by created_at')
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])

        when:
        ApiErrorException refused = null
        try {
            if (producer == 'person') {
                acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(), AnswerReader.read(ANSWER), 'Fictional findings checked.')
            } else {
                acts.trySending(groupId(GROUP), runId(), stepId(), caller())
                drained()
            }
        } catch (ApiErrorException rejection) {
            refused = rejection
        }

        then:
        refused?.errorCode() == (producer == 'person' ? RefusalCode.STEP_MOVED_ON : null)
        def failure = shapeFailure()
        failure.production_id == null
        failure.purpose == null
        failure.resolved_at == null
        store.texts('select production_id::text from productions') == [opened]
        store.count('select count(*) from productions where ended_at is not null') == 0
        store.count('select count(*) from production_values') == 0
        store.count('select count(*) from run_step_send_attempts') == 0
        store.count('select count(*) from model_calls') == 0
        requested.empty
        RunBudget.spentAsRead(store.session, runId(), true) == RunBudget.Spend.NOTHING

        when:
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        engine.drive(groupId(GROUP), runId())
        drained()

        then:
        shapeFailure() == failure
        store.texts('select production_id::text from productions') == [opened]
        store.count("select count(*) from run_step_failures where reason::text = 'search_question_no_longer_matches'") == 1
        store.count('select count(*) from model_calls') == 0

        when:
        executor.destroy()
        holding()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        if (producer == 'person') {
            acts.answer(groupId(GROUP), runId(), stepId(), 1, caller(), AnswerReader.read(ANSWER), 'Fictional findings checked.')
            drained()
        }

        then:
        shapeFailure().id == failure.id
        shapeFailure().resolved_at != null
        shapeFailure().resolved_by == UUID.fromString(LibraryStore.FIRST_STEWARD)
        store.texts('select production_id::text from productions') == [opened]
        done()
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from production_values') == 4
        store.count('select count(*) from run_step_send_attempts') == (producer == 'model' ? 1 : 0)
        store.count('select count(*) from model_calls') == (producer == 'model' ? 1 : 0)
        store.count("select count(*) from run_step_send_attempts where answers_failure_id = ?::uuid", failure.id) == 0
        store.texts("select run_step_failure_id::text from run_step_failures where reason::text <> 'search_question_no_longer_matches' order by created_at") == oldFailures

        where:
        producer << ['model', 'person']
    }

    // DB-SPECIFIC: SQL in this feature uses PostgreSQL :: casts and now().
    def "a changed release after an ended historical try consumes no next attempt and resolves into the normal plan"() {
        given:
        store.session.sql('update workflow_steps set tries = 2').update()
        returned = new ToolCallOutcome(new CallOutcome.CameBack('{}', 10, 20, true, false, null, null), [:], [])
        toolAnswer = { request ->
            store.transactions().executeWithoutResult { RunRows.stopped(store, RUN, LibraryStore.FIRST_STEWARD) }
            returned
        }
        started()
        def firstProduction = store.texts('select production_id::text from productions').first()
        def firstAttempt = store.texts('select run_step_send_attempt_id::text from run_step_send_attempts').first()
        executor.destroy()
        holding(100_000, true, new BigDecimal('0.0100'), CodeStepsHeld.NONE, [changedWay()])
        store.session.sql('update run_stops set opened_again_at = greatest(now(), created_at), opened_again_by = ?::uuid')
                .params(LibraryStore.FIRST_STEWARD).update()

        when:
        engine.drive(groupId(GROUP), runId())
        drained()
        def failure = shapeFailure()

        then:
        failure.production_id == null
        failure.purpose == null
        store.texts('select production_id::text from productions') == [firstProduction]
        store.texts('select run_step_send_attempt_id::text from run_step_send_attempts') == [firstAttempt]
        store.count('select count(*) from productions where ended_at is not null') == 1
        store.count('select count(*) from model_calls') == 1
        requested.size() == 1
        RunBudget.spentAsRead(store.session, runId(), true).spent() == 30

        when:
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()

        then:
        shapeFailure() == failure
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from run_step_send_attempts') == 1
        requested.size() == 1

        when:
        toolAnswer = null
        returned = new ToolCallOutcome(new CallOutcome.CameBack(ANSWER, 10, 20, true, false, null, null), [:], [])
        executor.destroy()
        holding()
        acts.trySending(groupId(GROUP), runId(), stepId(), caller())
        drained()
        engine.drive(groupId(GROUP), runId())
        drained()

        then:
        shapeFailure().id == failure.id
        shapeFailure().resolved_at != null
        shapeFailure().resolved_by == UUID.fromString(LibraryStore.FIRST_STEWARD)
        store.texts('select production_id::text from productions order by try_number').first() == firstProduction
        store.texts('select run_step_send_attempt_id::text from run_step_send_attempts order by created_at').first() == firstAttempt
        store.count('select count(*) from productions') == 2
        store.texts('select try_number::text from productions order by try_number') == ['1', '2']
        store.count('select count(*) from model_calls') == 2
        store.count('select count(*) from run_step_send_attempts') == 2
        store.count('select count(*) from run_step_send_attempts where answers_failure_id is not null') == 0
        requested.size() == 2
        done()
    }

}
