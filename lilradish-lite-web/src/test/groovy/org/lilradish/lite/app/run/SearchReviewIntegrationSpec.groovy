package org.lilradish.lite.app.run

import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
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
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.StepAct
import org.lilradish.lite.domain.run.StepGround
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SearchReviewIntegrationSpec extends Specification {

    static final String GROUP = '56560000-0000-4000-8000-000000000001'
    static final String WORKFLOW = '56560000-0000-4000-8000-000000000002'
    static final String VERSION = '56560000-0000-4000-8000-000000000003'
    static final String STEP = '56560000-0000-4000-8000-000000000004'
    static final String RUN = '56560000-0000-4000-8000-000000000005'
    static final String PART = '56560000-0000-4000-8000-000000000006'
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
    StepActs acts
    ToolCallOutcome returned
    Closure<CallOutcome> reviewing
    List<org.lilradish.lite.domain.inference.CallRequest> reviews = [].asSynchronized()
    List<ToolCallRequest> requested = [].asSynchronized()
    List<List<Boolean>> madeOn = [].asSynchronized()
    List<List<String>> heldAtSending = [].asSynchronized()

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, 'search_review_' + (++databasesMade))
        store.group(GROUP, 'SEARCH', 'Demo Sample')
        store.member(GROUP, LibraryStore.FIRST_STEWARD, 'operator')
        StepRows.workflow(store, WORKFLOW, VERSION, GROUP, 'Demo Sample')
        searching(STEP, 0, 'search_demo')
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set search_body_standing = 'never',
                    reviewer_model = 'small', reviewer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).params(STEP).update()
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

    private void holding(long reviewerTakes = 100_000, boolean hasWay = true, CodeSteps held = CodeStepsHeld.NONE,
                         boolean hasProducer = true) {
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, held, new SearchWays(hasWay ? [DeployedModels.WEB] : []))
        writes = new EngineWrites(store.session)
        executor = EngineExecutors.of(store.database)
        tasks = EngineTaskBarrier.attach(executor)
        def deployed = new DeployedModel(new ModelName('general'), [], 100_000, 4.0G, 4000, [],
                [new DeployedTool(TOOL, ModelToolKind.WEB_SEARCH, 5,
                        [new ToolPrice(Currency.getInstance('USD'), new BigDecimal('0.0100'))])])
        def reviewer = new DeployedModel(new ModelName('small'), [], reviewerTakes, 4.0G, 4000, [])
        def catalog = new ModelCatalog(hasProducer ? [deployed, reviewer] : [reviewer])
        def ceilings = new CeilingReach(store.session, catalog, writes)
        def tools = { request, progress ->
            requested << request
            madeOn << [Thread.currentThread() instanceof EngineThread,
                       TransactionSynchronizationManager.isActualTransactionActive()]
            heldAtSending << store.texts('''
                    select tool || ' ' || most_uses || ' ' || (uses is null) || ' ' || (model_call_outcome is null)
                      from model_call_tool_uses order by model_call_id
                    ''')
            returned
        } as ToolModelCalls
        def ordinary = { request, progress ->
            reviews << request
            reviewing == null ? new CallOutcome.CameBack('{"decisions":{"body":{"outcome":"assured"}}}',
                    30, 40, true, false, null, null) : reviewing.call(request)
        } as ModelCalls
        def files = EngineWiring.files(store)
        def calls = new EngineCalls(tools, ordinary, store.transactions(), tree, writes, ceilings, snapshots, files)
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
        store.transactions().executeWithoutResult {
            StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, LibraryStore.FIRST_STEWARD, '{}')
            engine.planStarted(tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
        drained()
    }

    private void drained() {
        tasks.awaitDrained()
    }

    private UserId caller() {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        new UserId(store.session.sql('select user_id from subjects where subject_id = ?::uuid')
                .params(LibraryStore.FIRST_STEWARD).query(String).single())
    }

    def "a question keeps its historical code input while a removed changed or file source blocks sending"() {
        given:
        def codeStep = UUID.randomUUID().toString()
        def entry = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def originalLeaf = new Field(new FieldName('note'), null, null, new FieldShape.Text(1000),
                new HowMany.One(), new Demand.Given(true))
        def originalShape = nested ? new FieldShape.Nested([originalLeaf]) : new FieldShape.Text(1000)
        def originalField = new Field(new FieldName('receipt'), null, null, originalShape, new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def value = new JsonValue.JsonString('Demo Sample input')
        def returnedValue = nested ? new JsonValue.JsonObject([new JsonValue.JsonMember('note', value)]) : value
        def original = new ScriptedCodeStep('send_reply', [], [originalField], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', returnedValue)]) })
        def changedShape = change == 'file' ? new FieldShape.File([new MediaTypeName('text/plain')], 1000L) :
                new FieldShape.Plain(FieldKind.NUMBER)
        def currentShape = nested ? new FieldShape.Nested([
                new Field(new FieldName('note'), null, null, changedShape, new HowMany.One(),
                        new Demand.Given(true))]) : changedShape
        def current = new ScriptedCodeStep('send_reply', [], change == 'removed' ? [] : [
                new Field(new FieldName('receipt'), null, null, currentShape, new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ALWAYS, null))], false,
                { new JsonValue.JsonObject([]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        store.session.sql('delete from search_question_parts').update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from workflow_steps where workflow_step_id = ?::uuid').params(STEP).update()
        StepRows.codeStep(store, codeStep, VERSION, 0, 'code_demo', 'send_reply', 'code', 1)
        StepRows.question(store, entry, questionVersion, GROUP, 'Demo Sample question', 'Demo Sample instruction')
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: 'question',
                              side: 'takes', position: 0, name: 'evidence', mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: 'question',
                              side: 'gives', position: 0, name: 'answer', standing: 'always', mustBe: true])
        StepRows.step(store, STEP, VERSION, 1, 'question_demo', questionVersion, 'model', 1)
        StepRows.binding(store, UUID.randomUUID().toString(), VERSION, STEP, 'evidence', codeStep,
                nested ? 'receipt.note' : 'receipt')
        reviewing = { request -> new CallOutcome.CameBack(
                '{"values":{"answer":"Demo Sample answer"},"confidences":{}}', 10, 20, true, false, null, null) }
        executor.destroy()
        holding(100_000, true, CodeStepsHeld.of(original), false)
        started()
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def historical = before.run().steps()[1].newest().orElseThrow()
        def made = before.run().steps()[0].newest().orElseThrow().values().first()

        expect:
        historical.number() == null
        historical.inputs().first().source() == made.id()
        reviews.empty
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select reason::text from run_step_failures') == ['model_not_deployed']

        when:
        executor.destroy()
        holding(100_000, true, CodeStepsHeld.of(current))
        acts.trySending(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(STEP)),
                caller())
        drained()
        def blocked = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def target = blocked.run().steps()[1]
        def failure = target.failures().last()
        def ground = StepGround.of(blocked.run(), target, StepPositions.of(blocked.run(), target), null)

        then:
        failure.reason() == RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        failure.onTry() == historical.id()
        failure.source().field() == (nested ? 'receipt.note' : 'receipt')
        failure.source().maker().runStep() == blocked.run().steps()[0].runStep()
        target.newest().orElseThrow().id() == historical.id()
        target.newest().orElseThrow().number() == null
        target.newest().orElseThrow().inputs().first().source() == made.id()
        StepAct.TRY_SENDING.refusal(ground) == null
        StepAct.ANSWER.refusal(ground) == RefusalCode.STEP_MOVED_ON
        StepAct.ASK_AGAIN.refusal(ground) == RefusalCode.STEP_MOVED_ON
        reviews.empty
        requested.empty
        current.runs.get() == 0
        store.count('select count(*) from model_calls') == 0
        store.count('select count(*) from run_step_send_attempts') == 0
        store.count('select count(*) from model_call_tool_uses where uses > 0') == 0
        store.count('select count(*) from productions') == 2

        when:
        executor.destroy()
        holding(100_000, true, CodeStepsHeld.of(original))
        acts.trySending(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(STEP)),
                caller())
        drained()
        def recovered = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        then:
        reviews.size() == 1
        reviews.first().purpose() == ModelCallPurpose.PRODUCE
        AnswerReader.read(reviews.first().sent().user()).members().find { it.name() == 'takes' }.value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember('evidence', value)])
        recovered.run().steps()[1].newest().orElseThrow().id() == historical.id()
        recovered.run().steps()[1].newest().orElseThrow().inputs().first().source() == made.id()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select answers_failure_id::text from run_step_send_attempts') == [failure.id().value().toString()]
        store.count('select count(*) from productions') == 2
        original.runs.get() == 1
        done(2)

        where:
        nested | change
        false  | 'removed'
        false  | 'shape'
        false  | 'file'
        true   | 'removed'
        true   | 'shape'
        true   | 'file'
    }

    def "a produced question is reviewed from its saved input portion after its code source changes or disappears"() {
        given:
        def codeStep = UUID.randomUUID().toString()
        def entry = UUID.randomUUID().toString()
        def questionVersion = UUID.randomUUID().toString()
        def input = UUID.randomUUID().toString()
        def leaf = new Field(new FieldName('note'), null, null, new FieldShape.Text(1000), new HowMany.One(),
                new Demand.Given(true))
        def shape = nested ? new FieldShape.Nested([leaf]) : new FieldShape.Text(1000)
        def receipt = new Field(new FieldName('receipt'), null, null, shape,
                many ? new HowMany.Many(2) : new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def one = nested ? new JsonValue.JsonObject([new JsonValue.JsonMember('note',
                new JsonValue.JsonString('Demo Sample input'))]) : new JsonValue.JsonString('Demo Sample input')
        def value = many ? new JsonValue.JsonArray([one]) : one
        def original = new ScriptedCodeStep('send_reply', [], [receipt], false,
                { new JsonValue.JsonObject([new JsonValue.JsonMember('receipt', value)]) })
        def changed = new ScriptedCodeStep('send_reply', [], [], false,
                { new JsonValue.JsonObject([]) })
        // DB-SPECIFIC: PostgreSQL ALTER TYPE enum ADD VALUE.
        store.session.sql("alter type code_step add value if not exists 'send_reply'").update()
        store.session.sql('delete from search_question_parts').update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql('delete from workflow_steps where workflow_step_id = ?::uuid').params(STEP).update()
        StepRows.codeStep(store, codeStep, VERSION, 0, 'code_demo', 'send_reply', 'code', 1)
        StepRows.question(store, entry, questionVersion, GROUP, 'Demo Sample question', 'Demo Sample instruction')
        StepRows.field(store, [id: input, owner: questionVersion, ownerKind: 'question', side: 'takes',
                              position: 0, name: 'evidence', kind: nested ? 'fields' : 'text',
                              most: many ? 2 : null, mustBe: true])
        if (nested) {
            StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: 'question',
                                  side: 'takes', parent: input, position: 0, name: 'note', mustBe: true])
        }
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: questionVersion, ownerKind: 'question',
                              side: 'gives', position: 0, name: 'answer', standing: 'never', mustBe: true])
        StepRows.step(store, STEP, VERSION, 1, 'question_demo', questionVersion, 'model', 1, 'small')
        StepRows.binding(store, UUID.randomUUID().toString(), VERSION, STEP, 'evidence', codeStep, 'receipt')
        reviewing = { request -> request.purpose() == ModelCallPurpose.PRODUCE ?
                new CallOutcome.CameBack('{"values":{"answer":"Demo Sample answer"},"confidences":{}}', 10, 20, true, false, null, null) :
                new CallOutcome.TurnedAway(new TurnAway('Demo Sample busy', false)) }
        executor.destroy()
        holding(100_000, true, CodeStepsHeld.of(original))
        started()
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def production = before.run().steps()[1].newest().orElseThrow()
        def source = before.run().steps()[0].newest().orElseThrow().values().first()
        def sent = AnswerReader.read(reviews.find { it.purpose() == ModelCallPurpose.PRODUCE }.sent().user())

        expect:
        original.runs.get() == 1
        requested.empty
        reviews.size() == 2
        production.inputs().first().source() == source.id()
        sent.members().find { it.name() == 'takes' }.value() ==
                new JsonValue.JsonObject([new JsonValue.JsonMember('evidence', value)])
        store.count('select count(*) from productions') == 2
        store.count('select count(*) from model_calls') == 2

        when:
        reviewing = { request -> new CallOutcome.CameBack('{"decisions":{"answer":{"outcome":"assured"}}}', 30, 40, true, false, null, null) }
        executor.destroy()
        holding(100_000, true, absent ? CodeStepsHeld.NONE : CodeStepsHeld.of(changed))
        acts.trySending(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(STEP)),
                caller())
        drained()
        def after = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def reviewed = AnswerReader.read(reviews.last().sent().user())

        then:
        reviews.size() == 3
        reviews.last().purpose() == ModelCallPurpose.REVIEW
        reviewed.members().find { it.name() == 'instruction' }.value() == sent.members().find { it.name() == 'instruction' }.value()
        reviewed.members().find { it.name() == 'takes' }.value() == sent.members().find { it.name() == 'takes' }.value()
        after.run().steps()[1].newest().orElseThrow().id() == production.id()
        after.run().steps()[1].newest().orElseThrow().inputs().first().source() == source.id()
        after.run().steps()[0].newest().orElseThrow().values().first().id() == source.id()
        original.runs.get() == 1
        changed.runs.get() == 0
        store.count('select count(*) from productions') == 2
        store.count('select count(*) from model_calls') == 3
        store.count('select count(*) from run_step_holds') == 0
        store.count('select count(*) from run_step_failures') == 0
        store.count('select count(*) from model_call_tool_uses') == 0
        done(2)

        where:
        nested | many  | absent
        false  | false | true
        true   | false | true
        false  | true  | true
        true   | true  | false
    }

    def "a search review uses its named plain model and preserves the question and only records actually kept"() {
        given:
        returned = new ToolCallOutcome(new CallOutcome.CameBack(ANSWER, 10, 20, true, false, null, null),
                [(TOOL): 2L], recorded ? [new SearchObservation(TOOL, 'Demo Sample sought',
                'Demo Sample found' + (0 as char))] : [])

        when:
        started()

        then:
        requested.size() == 1
        reviews.size() == 1
        reviews.first().purpose() == ModelCallPurpose.REVIEW
        reviews.first().model().name().value() == 'small'
        reviews.first().mode() == null
        def payload = AnswerReader.read(reviews.first().sent().user())
        payload.members()*.name() == (recorded ? ['envelope_version', 'question', 'search_record', 'answer', 'deciding'] :
                ['envelope_version', 'question', 'answer', 'deciding'])
        payload.members().find { it.name() == 'question' }.value() == new JsonValue.JsonString(QUESTION)
        payload.members().find { it.name() == 'deciding' }.value() ==
                new JsonValue.JsonArray([new JsonValue.JsonString('body')])
        !reviews.first().sent().user().contains('confidences')
        !reviews.first().sent().user().contains('producer')
        !reviews.first().sent().user().contains('per_use')
        !reviews.first().sent().user().contains('uses')
        if (recorded) {
            def record = payload.members().find { it.name() == 'search_record' }.value()
            record.members()*.name() == ['searches', 'cut', 'not_as_it_came', 'left_out']
            record.members().find { it.name() == 'not_as_it_came' }.value() == new JsonValue.JsonBoolean(true)
            record.members().find { it.name() == 'searches' }.value().items().size() == 1
        }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select purpose::text from model_calls order by created_at') == ['produce', 'review']
        store.count('select count(*) from model_call_tool_uses') == 1
        store.count('select count(*) from model_call_tool_prices') == 1
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from reviews') == 1
        store.count('select count(*) from production_values where confidence is not null') == 0
        store.count('select count(*) from run_step_failures') == 0
        done()

        where:
        recorded << [true, false]
    }

    def "an unreadable plain review of a search keeps its response on the review call only"() {
        given:
        reviewing = { request -> new CallOutcome.Errored(
                'The model service returned a response this system could not read.',
                KeptAnswer.of('Demo Sample search review envelope')) }

        when:
        started()

        then:
        requested.size() == 1
        reviews.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('''
                select purpose::text || '|' || outcome::text || '|' ||
                       coalesce(unreadable_response, '<absent>') || '|' ||
                       unreadable_response_altered || '|' || unreadable_response_truncated
                  from model_calls order by created_at, model_call_id
                ''') == ['produce|came_back|<absent>|false|false',
                        'review|errored|Demo Sample search review envelope|false|false']
        store.count('''
                select count(*) from model_calls call join reviews review on review.model_call_id = call.model_call_id
                 where call.purpose = 'review' and call.unreadable_response = 'Demo Sample search review envelope'
                ''') == 1
        store.count('select count(*) from model_call_searches') == 1
    }

    def "a search answered by a person is reviewed by the ordinary model with no invented tool record"() {
        given:
        store.session.sql("update workflow_steps set producer = 'person', producer_model = null, producer_mode = null").update()
        started()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        def user = new UserId(store.session.sql('select user_id from subjects where subject_id = ?::uuid')
                .params(LibraryStore.FIRST_STEWARD).query(String).single())

        when:
        acts.answer(groupId(GROUP), runId(), new WorkflowStepId(UUID.fromString(STEP)), 1, user,
                AnswerReader.read(ANSWER), 'Demo Sample why')
        drained()
        def sent = AnswerReader.read(reviews.first().sent().user())

        then:
        requested.empty
        reviews.size() == 1
        reviews.first().purpose() == ModelCallPurpose.REVIEW
        sent.members().find { it.name() == 'question' }.value() == new JsonValue.JsonString(QUESTION)
        !sent.members().any { it.name() == 'search_record' }
        store.count('select count(*) from productions') == 1
        store.count("select count(*) from productions where producer = 'person' and ended_by_kind = 'person'") == 1
        store.count('select count(*) from model_calls') == 1
        store.count("select count(*) from model_calls where purpose = 'review'") == 1
        store.count('select count(*) from model_call_tool_uses') == 0
        store.count('select count(*) from model_call_searches') == 0
        done()
    }

    def "an actual search record too large for its reviewing model passes the production to a person without a call"() {
        given:
        executor.destroy()
        holding(2_000)
        returned = new ToolCallOutcome(new CallOutcome.CameBack(ANSWER, 10, 20, true, false, null, null),
                [(TOOL): 2L], [new SearchObservation(TOOL, 'Demo Sample sought', 'x' * 40_000)])

        when:
        started()

        then:
        requested.size() == 1
        reviews.empty
        store.count('select count(*) from model_calls') == 1
        store.count("select count(*) from run_step_send_attempts where purpose = 'review' and too_long") == 1
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from reviews') == 0
        store.count('select count(*) from run_step_holds') == 0
        store.count('select count(*) from run_step_failures') == 0
        def at = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def waiting = StepPositions.of(at.run()).first()
        waiting instanceof StepPosition.AwaitingReview
        waiting.sending() == org.lilradish.lite.domain.run.ReviewSending.TOO_LONG
        waiting.values()*.on() == [org.lilradish.lite.domain.run.WaitsOn.REVIEW_AT_GATE]
        !done()
    }

    def "a missing search way spends no try and its return releases the hold before producing and reviewing"() {
        given:
        executor.destroy()
        holding(100_000, false)
        started()
        def held = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        expect:
        requested.empty
        reviews.empty
        store.count('select count(*) from productions') == 0
        store.count('select count(*) from model_calls') == 0
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select reason::text from run_step_holds where released_at is null') == ['search_way_not_held']
        StepPositions.of(held.run()).first() instanceof StepPosition.HeldBack
        StepPositions.of(held.run()).first().reason() == org.lilradish.lite.domain.run.RunStepHoldReason.SEARCH_WAY_NOT_HELD

        when:
        executor.destroy()
        holding()
        engine.drive(groupId(GROUP), runId())
        drained()

        then:
        requested.size() == 1
        reviews.size() == 1
        store.count('select count(*) from productions') == 1
        store.count('select count(*) from productions where try_number = 1 and yielded') == 1
        store.count('select count(*) from run_step_holds where released_at is null') == 0
        store.count("select count(*) from run_step_holds where reason = 'search_way_not_held' and released_at is not null") == 1
        store.count('select count(*) from run_step_failures') == 0
        done()
    }

    def "a way removed after a real review turned away leaves the same production for a person without sending again"() {
        given:
        reviewing = { request -> new CallOutcome.TurnedAway(new TurnAway('Demo Sample busy', false)) }
        started()
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def production = store.texts('select production_id::text from productions')
        def before = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }

        expect:
        production.size() == 1
        requested.size() == 1
        reviews.size() == 1
        StepPositions.of(before.run()).first() instanceof StepPosition.AwaitingReview
        StepPositions.of(before.run()).first().sending() == org.lilradish.lite.domain.run.ReviewSending.TURNED_AWAY

        when:
        executor.destroy()
        holding(100_000, false)
        engine.drive(groupId(GROUP), runId())
        drained()
        def after = store.transactions().execute { snapshots.inTreeAsRead(groupId(GROUP), runId()) }
        def waiting = StepPositions.of(after.run()).first()

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select production_id::text from productions') == production
        requested.size() == 1
        reviews.size() == 1
        store.count('select count(*) from model_calls') == 2
        store.count('select count(*) from model_call_tool_uses') == 1
        store.count('select count(*) from model_call_tool_prices') == 1
        store.count("select count(*) from run_step_send_attempts where purpose = 'review' and unbuilt_reason = 'no_longer_declared'") == 1
        store.count('select count(*) from reviews') == 0
        store.count('select count(*) from run_step_failures') == 0
        waiting instanceof StepPosition.AwaitingReview
        waiting.sending() == org.lilradish.lite.domain.run.ReviewSending.NO_LONGER_DECLARED
        waiting.values()*.on() == [org.lilradish.lite.domain.run.WaitsOn.REVIEW_AT_GATE]
        !done()
    }
}
