package com.lilradish.lite.app.run

import static com.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.BEN_USER
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static com.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static com.lilradish.lite.testutil.library.LibraryStore.groupId

import com.lilradish.lite.app.currency.GroupCurrencies
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.library.ConstantJson
import com.lilradish.lite.app.run.fixture.StepRows
import com.lilradish.lite.app.run.fixture.TicketWorkflow
import com.lilradish.lite.domain.declaration.Asking
import com.lilradish.lite.domain.failure.RefusalCode
import com.lilradish.lite.domain.inference.CallOutcome
import com.lilradish.lite.domain.inference.Envelope
import com.lilradish.lite.domain.model.DeployedModel
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunStepHoldReason
import com.lilradish.lite.domain.run.ValueStanding
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.Pause
import com.lilradish.lite.testutil.library.LibraryStore
import com.lilradish.lite.testutil.run.RecordedModelCalls
import org.libprunus.core.error.ApiErrorException
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class LengthRefusalIntegrationSpec extends Specification {

    static final String CHECK = '00000009-0000-4000-8000-000000000e04'

    static final String CHECK_INPUT = '0000000d-0000-4000-8000-000000000e04'

    static final String CHILD = '00000006-0000-4000-8000-000000000e10'

    static final String CHILD_VERSION = '00000007-0000-4000-8000-000000000e10'

    static final String CHILD_TAKES = '0000000c-0000-4000-8000-000000000e10'

    static final String CHILD_STEP = '00000009-0000-4000-8000-000000000e10'

    static final String CHILD_INPUT = '0000000d-0000-4000-8000-000000000e10'

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    EngineWiring wiring

    RecordedModelCalls calls = new RecordedModelCalls()

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, 'length_refusal_' + (++databasesMade))
    }

    def cleanup() {
        wiring?.closing()
        wiring?.executor?.destroy()
    }

    private void held(int attempts = 2, boolean usedElsewhere = false, boolean inChild = false, boolean modelSource = false,
                      int sourceFields = 1) {
        TicketWorkflow.seed(store, attempts)
        if (sourceFields > 1) {
            (2..sourceFields).each { number ->
                def name = 'summary_' + number
                StepRows.field(store, [id: UUID.randomUUID().toString(), owner: TicketWorkflow.SUMMARISE_VERSION,
                                      ownerKind: 'question', side: 'gives', position: number, name: name,
                                      limit: 1000, mustBe: true, standing: 'never'])
                StepRows.field(store, [id: UUID.randomUUID().toString(), owner: TicketWorkflow.CONFIRM_VERSION,
                                      ownerKind: 'question', side: 'takes', position: number, name: name,
                                      limit: 1000, mustBe: true])
                StepRows.binding(store, UUID.randomUUID().toString(), TicketWorkflow.VERSION, CONFIRM,
                        name, SUMMARISE, name)
            }
        }
        if (modelSource) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    update workflow_steps set producer = 'model', producer_model = 'rewrite', producer_mode = 'ordinary',
                           tells_what_happened = true where workflow_step_id = ?::uuid
                    """).param(SUMMARISE).update()
            calls.scripted.answering(new CallOutcome.CameBack('{"values":{"summary":"' + 'a' * 1000 + '"},"confidences":{}}',
                    10, 20, true, false, null, null))
            calls.scripted.answering(new CallOutcome.CameBack('{"values":{"summary":"' + 'b' * 900 + '"},"confidences":{}}',
                    10, 20, true, false, null, null))
        }
        if (inChild) {
            StepRows.workflow(store, CHILD, CHILD_VERSION, GROUP, 'Confirm an account')
            StepRows.field(store, [id: CHILD_TAKES, owner: CHILD_VERSION, ownerKind: 'workflow', side: 'takes',
                                  position: 1, name: 'summary', limit: 1000, mustBe: true])
            StepRows.step(store, CHILD_STEP, CHILD_VERSION, 1, 'confirm', TicketWorkflow.CONFIRM_VERSION, 'model', 1)
            StepRows.binding(store, CHILD_INPUT, CHILD_VERSION, CHILD_STEP, 'summary', null, 'summary')
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql('update workflow_steps set label = ? where workflow_step_id = ?::uuid')
                    .params('\u8981\u7d04', SUMMARISE).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql('update workflow_steps set label = ? where workflow_step_id = ?::uuid')
                    .params('\u78ba\u8a8d', CHILD_STEP).update()
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    update workflow_steps set pinned_version_id = ?::uuid, pinned_kind = 'workflow',
                           producer = null, producer_model = null, producer_mode = null, tries = null
                     where workflow_step_id = ?::uuid
                    """).params(CHILD_VERSION, CONFIRM).update()
        }
        if (usedElsewhere) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql('update workflow_steps set position = 3 where workflow_step_id = ?::uuid')
                    .param(CONFIRM).update()
            StepRows.step(store, CHECK, TicketWorkflow.VERSION, 2, 'check', TicketWorkflow.CONFIRM_VERSION, 'person', 1)
            StepRows.binding(store, CHECK_INPUT, TicketWorkflow.VERSION, CHECK, 'summary', SUMMARISE, 'summary')
        }
        if (!inChild) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                     where workflow_step_id = ?::uuid
                    """).param(CONFIRM).update()
        }
        def deployed = [new DeployedModel(new ModelName('general'), [], 600, 4.0G, 4_000, [], [])]
        if (modelSource) deployed << new DeployedModel(new ModelName('rewrite'), [], 2_000, 4.0G, 4_000, [], [])
        def models = new ModelCatalog(deployed)
        wiring = new EngineWiring(store, calls, CodeStepsHeld.NONE, models)
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
        wiring.drained()
        if (!modelSource) {
            wiring.acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER,
                    sourceAnswer(sourceFields, 'a' * 1000), 'A complete account of the incident.')
            wiring.drained()
        }
        wiring.assure(groupId(GROUP), runId(), stepId(SUMMARISE), 1, BEN_USER,
                (1..sourceFields).collect { it == 1 ? 'summary' : 'summary_' + it } as Set)
        wiring.drained()
        if (usedElsewhere) {
            wiring.acts.answer(groupId(GROUP), runId(), stepId(CHECK), 1, ANN_USER,
                    ConstantJson.sent('{"approved":"true","note":"Checked the account."}'), 'The account is complete.')
            wiring.drained()
        }
        def root = context()
        def target = inChild ? root.beneath(root.run().step(stepId(CONFIRM)).orElseThrow()).run()
                .step(stepId(CHILD_STEP)).orElseThrow() : root.run().step(stepId(CONFIRM)).orElseThrow()
        assert target.hold().reason() == RunStepHoldReason.TOO_LONG
        assert calls.scripted.requests.size() == (modelSource ? 1 : 0)
    }

    private def context() {
        wiring.snapshots.inTreeAsRead(groupId(GROUP), runId())
    }

    private static def sourceAnswer(int fields, String text) {
        ConstantJson.sent('{' + (1..fields).collect {
            '"' + (it == 1 ? 'summary' : 'summary_' + it) + '":"' + text + '"'
        }.join(',') + '}')
    }

    private def sourceValue() {
        context().run().step(stepId(SUMMARISE)).orElseThrow().tries()[0].values()[0].id()
    }

    private UUID holdId() {
        def found = context()
        new RunReadDetails(store.session, found).lengthHold(found.run().step(stepId(CONFIRM)).orElseThrow())
    }

    private static RunId runId() {
        new RunId(UUID.fromString(RUN))
    }

    private static WorkflowStepId stepId(String id) {
        new WorkflowStepId(UUID.fromString(id))
    }

    private def readSource(ModelCatalog models, String requested = SUMMARISE) {
        def roles = new GroupRoles(store.session)
        def currencies = new GroupCurrencies(store.session, new TransactionTemplate(store.transactionManager()),
                roles, models)
        new RunSteps(store.session, roles, wiring.snapshots, store.transactionManager(), Deployments.DEEP_ENOUGH,
                currencies, models).step(groupId(GROUP), runId(), stepId(requested), ANN_USER)
    }

    def "a person may reject their own standing source once, recording actual quantities and spending only that step's attempts"() {
        given:
        held(attempts)
        def value = sourceValue()
        def hold = holdId()
        def sourceHistory = store.digestOf('production_values')
        def found = context()
        def refusedBy = found.run().step(stepId(CONFIRM)).orElseThrow()
        def question = refusedBy.planned().runs()
        def envelope = Envelope.toProduce(Asking.told(question.gives(), question.lists()), question.instruction())
        def payload = store.texts('select payload from run_step_send_attempts where payload is not null')[0]
        def actual = SentText.measure(envelope, payload)
        def expectedExcess = Math.max(0L, (actual.characters() + 3L).intdiv(4L) - 600L)
        def excessWords = expectedExcess.toString() + (expectedExcess == 1 ? ' unit' : ' units')

        when:
        wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, hold, value)
        wiring.drained()

        then:
        def now = context()
        def source = now.run().step(stepId(SUMMARISE)).orElseThrow()
        ValueStanding.of(source.tries()[0], source.tries()[0].values()[0]) == ValueStanding.REFUSED_FOR_LENGTH
        source.tries()*.number() == numbers
        source.tries()[0].values()[0].id() == value
        source.tries()[0].values()[0].value() == found.run().step(stepId(SUMMARISE)).orElseThrow().tries()[0].values()[0].value()
        store.digestOf('production_values') == sourceHistory
        now.run().step(stepId(CONFIRM)).orElseThrow().hold().reason() == RunStepHoldReason.TOO_LONG
        holdId() == hold
        calls.scripted.requests.empty
        store.count('select count(*) from reviews where for_length') == 1
        store.count('select count(*) from reviews where not for_length') == 1
        store.texts('select length_units || \' \' || excess_units from review_decisions where for_length') == ["251 ${expectedExcess}".toString()]
        store.texts('select explanation from review_decisions where for_length') == [
                "Rejected for its length, not for what it says: it took up about 251 units of what a later step would send, " +
                        "and that was about ${excessWords} more than its model takes. Give it again, shorter.".toString()]

        and: "the question's history reads stored quantities and the held step even after a different deployment"
        def original = readSource(new ModelCatalog([new DeployedModel(new ModelName('general'), [], 600, 4.0G, 4_000, [], [])]))
                .triesMade()[0].values()[0].decision()
        original.outcome() == 'refused'
        original.lengthUnits() == '251'
        original.excessUnits() == expectedExcess.toString()
        original.lengthHeld().run().runId() == UUID.fromString(RUN)
        original.lengthHeld().stepId() == UUID.fromString(CONFIRM)
        readSource(new ModelCatalog([new DeployedModel(new ModelName('general'), [], 20_000, 1.0G, 4_000, [], [])]))
                .triesMade()[0].values()[0].decision() == original

        and: "a repeated confirmation cannot reject another value, spend another attempt or write half a review"
        def before = store.contents()

        when:
        wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, hold, value)

        then:
        def refusal = thrown(ApiErrorException)
        refusal.errorCode() == RefusalCode.LENGTH_VALUE_NOT_STANDING
        store.contents() == before

        where:
        attempts | numbers
        2        | [1, 2]
        1        | [1]
    }

    def "a confirmation naming a length hold left behind changes nothing"() {
        given:
        held()
        def value = sourceValue()
        def oldHold = holdId()
        wiring.acts.trySending(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER)
        wiring.drained()
        assert holdId() != oldHold
        def before = store.contents()

        when:
        wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, oldHold, value)

        then:
        def refusal = thrown(ApiErrorException)
        refusal.errorCode() == RefusalCode.STEP_NOT_HELD_FOR_LENGTH
        store.contents() == before
        calls.scripted.requests.empty
    }

    def "a new standing production can read only this maker field's earlier refusal for this held step"() {
        given:
        held()
        def oldValue = sourceValue()
        wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, holdId(), oldValue)
        wiring.drained()
        wiring.acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 2, ANN_USER,
                ConstantJson.sent('{"summary":"' + 'b' * 900 + '"}'), 'The account has been shortened.')
        wiring.drained()
        wiring.assure(groupId(GROUP), runId(), stepId(SUMMARISE), 2, BEN_USER, ['summary'] as Set)
        wiring.drained()
        def at = context()
        def source = at.run().step(stepId(SUMMARISE)).orElseThrow()
        def target = at.run().step(stepId(CONFIRM)).orElseThrow()
        def read = new RunReadDetails(store.session, at)
        def before = store.contents()

        expect:
        source.tries()[1].values()[0].id() != oldValue
        ValueStanding.of(source.tries()[1], source.tries()[1].values()[0]) == ValueStanding.STANDS
        read.previousLengthUnits([source.tries()[1].values()[0].id(), oldValue], target)
                .get(source.tries()[1].values()[0].id()) == 251L
        !read.previousLengthUnits([source.tries()[1].values()[0].id(), oldValue], target).containsKey(oldValue)
        read.previousLengthUnits([source.tries()[1].values()[0].id()], source).isEmpty()
        def current = readSource(new ModelCatalog([new DeployedModel(new ModelName('general'), [], 600, 4.0G, 4_000, [], [])]),
                CONFIRM).step().length()
        current.values().size() == 1
        current.values()[0].valueId() == source.tries()[1].values()[0].id().value()
        current.values()[0].units() == '226'
        current.values()[0].previousUnits() == '251'
        store.contents() == before
        calls.scripted.requests.empty
    }

    def "another real production using the source prevents refusal even while this step stays held on its length"() {
        given:
        held(2, true)
        def value = sourceValue()
        def hold = holdId()
        def read = new RunReadDetails(store.session, context())
        assert read.used(value)*.stepId() == [UUID.fromString(CHECK)]
        def before = store.contents()

        when:
        wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, hold, value)

        then:
        def refusal = thrown(ApiErrorException)
        refusal.errorCode() == RefusalCode.LENGTH_VALUE_USED
        store.contents() == before
        store.count('select count(*) from reviews where for_length') == 0
        holdId() == hold
        calls.scripted.requests.empty
    }

    def "a model already carrying the source uses it before any value comes back, even if its answer is lost"() {
        given:
        TicketWorkflow.seed(store, 2)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).param(CONFIRM).update()
        def models = new ModelCatalog([new DeployedModel(new ModelName('general'), [], 2_000, 4.0G, 4_000, [], [])])
        def gate = new Pause()
        calls.scripted.pausing(gate, [], new CallOutcome.Errored('The provider disconnected before an answer arrived.', null))
        wiring = new EngineWiring(store, calls, CodeStepsHeld.NONE, models)
        store.transactions().executeWithoutResult {
            TicketWorkflow.run(store)
            wiring.engine.planStarted(wiring.tree.lock(groupId(GROUP), runId()).orElseThrow())
        }
        wiring.drained()
        wiring.acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER,
                ConstantJson.sent('{"summary":"' + 'a' * 1000 + '"}'), 'A complete account of the incident.')
        wiring.drained()

        when:
        wiring.assure(groupId(GROUP), runId(), stepId(SUMMARISE), 1, BEN_USER, ['summary'] as Set)
        gate.awaitReached()

        then:
        def at = context()
        def value = sourceValue()
        def target = at.run().step(stepId(CONFIRM)).orElseThrow()
        target.hold() == null
        def consuming = target.tries()[0]
        !consuming.yielded()
        consuming.endedAt() == null
        consuming.values().empty
        consuming.calls().size() == 1
        consuming.calls()[0].outcome() == null
        calls.scripted.requests.size() == 1
        calls.scripted.requests[0].model().name().value() == 'general'
        calls.scripted.requests[0].sent().user().contains('a' * 1000)
        calls.madeOn == [[true, false]]
        store.count('select count(*) from model_calls') == 1
        def read = new RunReadDetails(store.session, at)
        read.used(value)*.stepId() == [UUID.fromString(CONFIRM)]

        when:
        gate.release()
        wiring.drained()

        then:
        !context().run().step(stepId(CONFIRM)).orElseThrow().tries()[0].yielded()
        context().run().step(stepId(CONFIRM)).orElseThrow().tries()[0].calls()[0].outcome() != null
        com.lilradish.lite.domain.run.StepPositions.of(context().run())[1].why() instanceof
                com.lilradish.lite.domain.run.StepFailure.TriesSpent
        new RunReadDetails(store.session, context()).used(value)*.stepId() == [UUID.fromString(CONFIRM)]
        calls.scripted.requests.size() == 1

        when:
        wiring.close()
        def smaller = new ModelCatalog([new DeployedModel(new ModelName('general'), [], 600, 4.0G, 4_000, [], [])])
        wiring = new EngineWiring(store, calls, CodeStepsHeld.NONE, smaller)
        wiring.started()
        wiring.drained()
        wiring.acts.askAgain(groupId(GROUP), runId(), stepId(CONFIRM), 2, ANN_USER)
        wiring.drained()

        then:
        context().run().step(stepId(CONFIRM)).orElseThrow().hold().reason() == RunStepHoldReason.TOO_LONG
        !context().run().step(stepId(CONFIRM)).orElseThrow().tries()[0].yielded()
        context().run().step(stepId(CONFIRM)).orElseThrow().tries()*.number() == [1, null]
        calls.scripted.requests.size() == 1
        def length = readSource(smaller, CONFIRM).step().length()
        length.fixedUnits().toLong() < length.limitUnits().toLong()
        length.limitUnits().toLong() < length.wholeUnits().toLong()
        def row = length.values()[0]
        row.valueId() == value.value()
        !row.mayRefuse()
        row.used()*.stepId() == [UUID.fromString(CONFIRM)]
        def hold = holdId()
        def before = store.contents()

        when:
        wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, hold, value)

        then:
        def refusal = thrown(ApiErrorException)
        refusal.errorCode() == RefusalCode.LENGTH_VALUE_USED
        store.contents() == before
        sourceValue() == value
        holdId() == hold
        context().run().step(stepId(SUMMARISE)).orElseThrow().tries()*.number() == [1]
        calls.scripted.requests.size() == 1
        store.count('select count(*) from reviews where for_length') == 0

        cleanup:
        gate.release()
        wiring.drained()

    }

    def "reading one or sixteen sources keeps the query count while restoring each field's own refusal history"() {
        given:
        def queryCounts = []
        [1, 16].each { fields ->
            def localCalls = new RecordedModelCalls()
            calls = localCalls
            held(2, false, false, false, fields)
            def rejected = sourceValue()
            wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, holdId(), rejected)
            wiring.drained()
            wiring.acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 2, ANN_USER,
                    sourceAnswer(fields, 'b' * 900), 'The complete account has been shortened.')
            wiring.drained()
            wiring.assure(groupId(GROUP), runId(), stepId(SUMMARISE), 2, BEN_USER,
                    (1..fields).collect { it == 1 ? 'summary' : 'summary_' + it } as Set)
            wiring.drained()
            def made = context().run().step(stepId(SUMMARISE)).orElseThrow().tries()[1].values()
            def statements = []
            def counted = new com.lilradish.lite.testutil.CountingDataSource(store.database, statements)
            def session = org.springframework.jdbc.core.simple.JdbcClient.create(counted)
            def manager = store.transactionManager(counted)
            def models = wiring.engine.catalog
            def roles = new GroupRoles(session)
            def snapshots = new RunSnapshots(session, CodeStepsHeld.NONE, new com.lilradish.lite.app.search.SearchWays([]))
            def currencies = new GroupCurrencies(session, new TransactionTemplate(manager), roles, models)
            def steps = new RunSteps(session, roles, snapshots, manager, Deployments.DEEP_ENOUGH, currencies, models)

            def length = steps.step(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER).step().length()
            assert length.values().size() == fields
            assert length.values().collectEntries { [(it.valueId()): it.inputs()] } ==
                    made.collectEntries { [(it.id().value()): [it.field()]] }
            assert length.values().every { it.units() == '226' && it.used().isEmpty() && it.from().stepId() == UUID.fromString(SUMMARISE) }
            assert length.values().collectEntries { [(it.valueId()): it.previousUnits()] } ==
                    made.collectEntries { [(it.id().value()): it.field() == 'summary' ? '251' : null] }
            queryCounts << statements.size()
            println "LENGTH_READ_QUERY_COUNT fields=${fields} total=${statements.size()}"
            wiring.close()
            if (fields == 1) store = LibraryStore.forCase(server, 'length_refusal_' + (++databasesMade))
        }

        expect:
        queryCounts[0] > 0
        queryCounts[1] == queryCounts[0]
    }

    def "a child starting alone does not use its parent's source and a length refusal spends the parent's attempt"() {
        given:
        held(2, false, true)
        def root = context()
        def child = root.beneath(root.run().step(stepId(CONFIRM)).orElseThrow())
        def target = child.run().step(stepId(CHILD_STEP)).orElseThrow()
        def value = sourceValue()
        def read = new RunReadDetails(store.session, child)
        def hold = read.lengthHold(target)
        def maker = read.maker(value)
        def history = store.digestOf('production_values')
        assert read.used(value).empty
        assert maker.run().run().value() == UUID.fromString(RUN)
        assert maker.stepId() == UUID.fromString(SUMMARISE)

        when:
        wiring.acts.refuseForLength(groupId(GROUP), child.run().run(), stepId(CHILD_STEP), ANN_USER, hold, value)
        wiring.drained()

        then:
        def now = context()
        def source = now.run().step(stepId(SUMMARISE)).orElseThrow()
        source.tries()*.number() == [1, 2]
        ValueStanding.of(source.tries()[0], source.tries()[0].values()[0]) == ValueStanding.REFUSED_FOR_LENGTH
        source.tries()[0].values()[0].id() == value
        store.digestOf('production_values') == history
        def sameChild = now.beneath(now.run().step(stepId(CONFIRM)).orElseThrow())
        sameChild.run().run() == child.run().run()
        new RunReadDetails(store.session, sameChild).numbered(sameChild.run().run()) == read.numbered(child.run().run())
        def sameTarget = sameChild.run().step(stepId(CHILD_STEP)).orElseThrow()
        sameTarget.hold().reason() == RunStepHoldReason.TOO_LONG
        new RunReadDetails(store.session, sameChild).lengthHold(sameTarget) == hold
        calls.scripted.requests.empty
        store.count('select count(*) from reviews where for_length') == 1
        def decision = readSource(new ModelCatalog([new DeployedModel(new ModelName('general'), [], 600, 4.0G, 4_000, [], [])]))
                .triesMade()[0].values()[0].decision()
        decision.lengthHeld().run().runId() == child.run().run().value()
        decision.lengthHeld().stepId() == UUID.fromString(CHILD_STEP)
        decision.lengthHeld().label() == '\u78ba\u8a8d'
    }

    def "a model making the source again is actually told the system's quantified refusal without asking the person for words"() {
        given:
        held(2, false, false, true)
        def value = sourceValue()
        def hold = holdId()
        def firstSent = calls.scripted.requests[0].sent()

        when:
        wiring.acts.refuseForLength(groupId(GROUP), runId(), stepId(CONFIRM), ANN_USER, hold, value)
        wiring.drained()

        then:
        calls.scripted.requests.size() == 2
        calls.scripted.scriptsLeft() == 0
        calls.madeOn == [[true, false], [true, false]]
        def nextSent = calls.scripted.requests[1].sent()
        nextSent.system() == firstSent.system()
        nextSent.user().contains('Rejected for its length, not for what it says: it took up about 251 units of what a later step would send, and that was about 47 units more than its model takes. Give it again, shorter.')
        !firstSent.user().contains('Rejected for its length')
        def source = context().run().step(stepId(SUMMARISE)).orElseThrow()
        source.tries()*.number() == [1, 2]
        ValueStanding.of(source.tries()[0], source.tries()[0].values()[0]) == ValueStanding.REFUSED_FOR_LENGTH
        source.tries()[0].values()[0].id() == value
        source.tries()[1].values()[0].value().value() == 'b' * 900
        context().run().step(stepId(CONFIRM)).orElseThrow().hold().reason() == RunStepHoldReason.TOO_LONG
        holdId() == hold
        store.count('select count(*) from reviews where for_length') == 1
    }
}
