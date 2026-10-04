package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.ANN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.BEN_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CAT_USER
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_QUESTION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY_IN
import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.charset.StandardCharsets
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.files.FileDeployment
import org.lilradish.lite.app.filling.ValueProblemsRefusal
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.library.ConstantJson
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.file.StoredFile
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.filling.FillProblem
import org.lilradish.lite.domain.filling.FillReason
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepFailure
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepPositions
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.CountingDataSource
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.lilradish.lite.web.MultipartFiles
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.util.unit.DataSize
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class StepActsIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String ANNS_RUN = "00000008-0000-4000-8000-000000000e02"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    RunSnapshots snapshots

    EngineExecutor executor

    RunEngine engine

    StepActs acts

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "step_acts_" + (++databasesMade))
        wire()
        TicketWorkflow.seed(store)
    }

    def cleanup() {
        executor.destroy()
    }

    private void wire() {
        def session = store.session
        tree = new RunTree(session)
        snapshots = new RunSnapshots(session, CodeStepsHeld.NONE, new SearchWays([]))
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        def heldFiles = EngineWiring.files(store)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(EngineWiring.withoutTools(), new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings, snapshots, heldFiles),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor, heldFiles),
                DeployedModels.HELD, ceilings, new GroupRoles(session), Deployments.DEEP_ENOUGH, heldFiles)
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine,
                heldFiles, EngineWiring.fileDeployment())
    }

    def "root text actions add no file gate or promotion snapshot"() {
        given:
        def statements = Collections.synchronizedList([])
        executor.destroy()
        store = new LibraryStore(new CountingDataSource(store.database, statements))
        wire()
        if (action == 'answer') {
            started()
        } else {
            waitingOnReview()
        }
        statements.clear()

        when:
        if (action == 'answer') {
            acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 1, CAT_USER,
                    ConstantJson.sent('{"summary":"Demo Sample summary"}'), 'Done.')
        } else {
            acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER,
                    action == 'assure' ? assured() : refused())
        }

        then:
        statements.count { it.contains('select run.run_id, run.root_run_id, run.parent_run_id') } == snapshotsRead
        !statements.any { it.contains('with recursive file_fields') }
        !statements.any { it.contains('insert into run_file_holders') }
        store.count('select count(*) from run_file_holders') == 0

        where:
        action   | snapshotsRead
        'answer' | 3
        'assure' | 4
        'refuse' | 3
    }

    def "Answer it here stores a person's file under the same run"() {
        given:
        String question = UUID.randomUUID().toString()
        String questionVersion = UUID.randomUUID().toString()
        String field = UUID.randomUUID().toString()
        String workflow = UUID.randomUUID().toString()
        String workflowVersion = UUID.randomUUID().toString()
        String step = UUID.randomUUID().toString()
        String run = UUID.randomUUID().toString()
        StepRows.question(store, question, questionVersion, GROUP, 'Demo Sample question')
        StepRows.field(store, [id: field, owner: questionVersion, ownerKind: 'question', side: 'gives',
                               position: 1, name: 'photo', kind: 'file', mustBe: true, standing: 'always'])
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update declaration_fields set file_types = array['image/png'], file_maximum_bytes = 100 " +
                "where declaration_field_id = ?::uuid").params(field).update()
        StepRows.workflow(store, workflow, workflowVersion, GROUP, 'Demo Sample workflow')
        StepRows.step(store, step, workflowVersion, 1, 'attach', questionVersion, 'person', 1)
        store.transactions().executeWithoutResult {
            StepRows.run(store, run, GROUP, 1, workflow, workflowVersion, CAT, '{}')
            engine.planStarted(tree.lock(groupId(GROUP), runId(run)).orElseThrow())
        }
        String wire = '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n' +
                '{"values":{"photo":{"upload":0,"name":"actual.png"}},"why":"Done."}' +
                '\r\n--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="ignored.txt"\r\n' +
                'Content-Type: image/png\r\n\r\nabc\r\n--Aa--\r\n'
        def request = new MockHttpServletRequest('PUT', '/')
        request.setContentType('multipart/form-data; boundary=Aa')
        request.setContent(wire.getBytes(StandardCharsets.ISO_8859_1))

        when:
        MultipartFiles.open(request, 1024).withCloseable { multipart ->
            def json = multipart.document()
            acts.answerFiles(groupId(GROUP), runId(run), stepId(step), 1, CAT_USER,
                    ConstantJson.sent(json.path('values').toString()), json.path('why').asString(), multipart)
        }

        then:
        store.texts('select original_name from stored_files') == ['actual.png']
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def ids = store.texts('select file_id::text from run_file_holders where run_id = ?::uuid', run)
        ids.size() == 1
        // DB-SPECIFIC: PostgreSQL ::text casts.
        def values = store.texts('select value::text from production_values')
        values.size() == 1
        def stored = StoredFile.fromKept(ConstantJson.stored(values[0]))
        stored.id().toString() == ids[0]
        stored.name().value() == 'actual.png'
        stored.type().value() == 'image/png'
        stored.size() == 3L
        def opened = EngineWiring.files(store).open(runId(run), groupId(GROUP), stored.id())
        opened.file() == stored
        opened.content().withCloseable { it.readAllBytes() == 'abc'.getBytes(StandardCharsets.UTF_8) }
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts('select file_id::text from stored_files') == ids

    }

    def "Answer it here counts all newly uploaded fields and keeps the try untouched when one byte over"() {
        given:
        String question = UUID.randomUUID().toString()
        String questionVersion = UUID.randomUUID().toString()
        String workflow = UUID.randomUUID().toString()
        String workflowVersion = UUID.randomUUID().toString()
        String step = UUID.randomUUID().toString()
        String run = UUID.randomUUID().toString()
        StepRows.question(store, question, questionVersion, GROUP, 'Demo Sample upload question')
        ['photo', 'copy'].eachWithIndex { name, index ->
            String field = UUID.randomUUID().toString()
            StepRows.field(store, [id: field, owner: questionVersion, ownerKind: 'question', side: 'gives',
                                   position: index + 1, name: name, kind: 'file', mustBe: true, standing: 'always'])
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("update declaration_fields set file_types = array['image/png'], file_maximum_bytes = 100 " +
                    "where declaration_field_id = ?::uuid").params(field).update()
        }
        StepRows.workflow(store, workflow, workflowVersion, GROUP, 'Demo Sample upload workflow')
        StepRows.step(store, step, workflowVersion, 1, 'attach', questionVersion, 'person', 1)
        store.transactions().executeWithoutResult {
            StepRows.run(store, run, GROUP, 1, workflow, workflowVersion, CAT, '{}')
            engine.planStarted(tree.lock(groupId(GROUP), runId(run)).orElseThrow())
        }
        def limits = new FileDeployment(DataSize.ofBytes(100), 2, DataSize.ofBytes(100))
        def boundedFiles = EngineWiring.files(store, limits)
        def boundedActs = new StepActs(store.session, store.transactions(), new GroupRoles(store.session), tree,
                snapshots, new EngineWrites(store.session), engine, boundedFiles, limits)
        def before = stepRows()
        Closure<Void> answer = { String second ->
            String wire = '--Aa\r\nContent-Disposition: form-data; name="document"\r\n\r\n' +
                    '{"values":{"photo":{"upload":0,"name":"one.png"},' +
                    '"copy":{"upload":1,"name":"two.png"}},"why":"Done."}' +
                    '\r\n--Aa\r\nContent-Disposition: form-data; name="upload0"; filename="one.png"\r\n' +
                    'Content-Type: image/png\r\n\r\n' + ('a' * 50) + '\r\n' +
                    '--Aa\r\nContent-Disposition: form-data; name="upload1"; filename="two.png"\r\n' +
                    'Content-Type: image/png\r\n\r\n' + second + '\r\n--Aa--\r\n'
            def request = new MockHttpServletRequest('PUT', '/')
            request.setContentType('multipart/form-data; boundary=Aa')
            request.setContent(wire.getBytes(StandardCharsets.ISO_8859_1))
            MultipartFiles.open(request, 1024).withCloseable { multipart ->
                def json = multipart.document()
                boundedActs.answerFiles(groupId(GROUP), runId(run), stepId(step), 1, CAT_USER,
                        ConstantJson.sent(json.path('values').toString()), json.path('why').asString(), multipart)
            }
            null
        }

        when:
        answer('b' * 51)

        then:
        def refusal = thrown(ApiErrorException)
        refusalOf(refusal) == RefusalCode.BODY_UNUSABLE
        stepRows() == before
        store.count('select count(*) from stored_files') == 0
        store.count('select count(*) from file_contents') == 0
        store.count('select count(*) from run_file_holders') == 0

        when:
        answer('b' * 50)

        then:
        stepRows() != before
        store.count('select count(*) from stored_files') == 2
        store.count('select count(*) from file_contents') == 2
        store.count('select count(*) from run_file_holders') == 2
        store.count('select count(*) from production_values') == 2
    }

    private static RunId runId(String spelled = RUN) {
        new RunId(UUID.fromString(spelled))
    }

    private static WorkflowStepId stepId(String spelled) {
        new WorkflowStepId(UUID.fromString(spelled))
    }

    private void started(String run = RUN, String starter = CAT) {
        store.transactions().executeWithoutResult {
            StepRows.run(store, run, GROUP, run == RUN ? 1 : 2, TicketWorkflow.WORKFLOW, TicketWorkflow.VERSION,
                    starter, TicketWorkflow.STARTED_WITH)
            engine.planStarted(tree.lock(groupId(GROUP), runId(run)).orElseThrow())
        }
    }

    private void waitingOnReview() {
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    private StepPosition positionOf(String step, String run = RUN) {
        def read = snapshots.asRead(groupId(GROUP), runId(run))
        StepPositions.of(read, read.step(stepId(step)).orElseThrow())
    }

    private List<String> triesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    private List<String> stepRows() {
        ["run_steps", "productions", "production_inputs", "production_values", "reviews", "review_decisions",
         "run_step_holds"].collect { store.digestOf(it) }
    }

    private static Map<String, StepActs.ReviewedRecord> assured() {
        [summary: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)]
    }

    private static Map<String, StepActs.ReviewedRecord> refused() {
        [summary: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "It says nothing about the fire.")]
    }

    private static RefusalCode refusalOf(ApiErrorException refused) {
        refused.errorCode() as RefusalCode
    }

    def "a review assuring every value waiting makes it stand, and the run goes on to ask the next step"() {
        given:
        waitingOnReview()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())

        then: "one review by Ann, assuring the summary"
        store.texts("select created_by || ' ' || created_by_kind || ' ' || for_length from reviews") ==
                ["${ANN} person false" as String]
        store.texts("select outcome || ' ' || coalesce(explanation, '-') from review_decisions") == ["assured -"]
        positionOf(SUMMARISE) instanceof StepPosition.Done

        and: "confirm asked of whoever may answer it, taking the summary that now stands"
        triesOf(CONFIRM) == ["1 person ${SYSTEM} open" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select taken.binding_id || ' ' || value.value::text || ' '
                       || (taken.source_run_step_id = source.run_step_id) || ' '
                       || (taken.source_run_step_id <> taken.run_step_id)
                  from production_inputs taken
                  join production_values value on value.production_value_id = taken.source_production_value_id
                  join productions source on source.production_id = value.production_id
                """) == ["${SUMMARY_IN} \"A printer fire.\" true true" as String]

        and: "and summarise asked nothing more"
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String]
    }

    // The act commits before the run continues, so a later failure belongs to the run.
    def "a review that lands is answered as landed, and a run that then cannot go on by itself is logged where it stopped"() {
        given:
        waitingOnReview()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update bindings set source_path = 'gone' where binding_id = ?::uuid").params(SUMMARY_IN).update()
        def engineLogger = LoggerFactory.getLogger(RunEngine) as Logger
        def logged = new SnapshottingAppender()
        logged.start()
        engineLogger.addAppender(logged)

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())

        then: "the review stands, and summarise with it"
        store.count("select count(*) from reviews") == 1
        positionOf(SUMMARISE) instanceof StepPosition.Done

        and: "confirm asked of nobody, and why the run did not go on logged once"
        triesOf(CONFIRM) == []
        def failures = logged.list.findAll { it.level == Level.ERROR }
        failures*.formattedMessage == ["Run ${RUN} of group ${GROUP} could not go on by itself" as String]
        failures[0].throwableProxy.className == IllegalStateException.name

        cleanup:
        engineLogger.detachAppender(logged)
    }

    def "a review refusing a value leaves the step owed its next try, which nobody has made yet"() {
        given:
        waitingOnReview()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())

        then:
        store.texts("select outcome || ' ' || explanation from review_decisions") ==
                ["refused It says nothing about the fire."]
        with(positionOf(SUMMARISE) as StepPosition.Owed) {
            number() == 2
            !open()
            !beyond()
        }

        and: "neither a next try nor the next step made by itself"
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String]
        triesOf(CONFIRM) == []
    }

    def "a value refused on the last try it declares fails the step"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = 1 where workflow_step_id = ?::uuid").params(SUMMARISE).update()
        waitingOnReview()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())

        then:
        with(positionOf(SUMMARISE) as StepPosition.Failed) {
            why() == new StepFailure.TriesSpent(1, 1)
        }

        and:
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String]
    }

    def "a review is refused where the reader produced the values waiting, writing nothing"() {
        given:
        started()
        StepRows.answered(store, SUMMARISE, ANN, "Ann's own.", [(SUMMARY): '"A printer fire."'])
        def before = stepRows()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == RefusalCode.REVIEW_OWN_PRODUCTION

        and:
        stepRows() == before
    }

    def "a review naming other than every value waiting is refused, writing nothing"() {
        given:
        waitingOnReview()
        def before = stepRows()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, BEN_USER, decisions)

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == RefusalCode.REVIEW_INCOMPLETE

        and:
        stepRows() == before

        where:
        decisions << [[:],
                      [summary: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null),
                       note: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)],
                      [note: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null)]]
    }

    def "a review of a try other than the one waiting, or of one decided already, is refused as moved on"() {
        given:
        waitingOnReview()
        if (decidedFirst) {
            acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())
        }
        def before = stepRows()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), number, BEN_USER, assured())

        then:
        def refusedAgain = thrown(ApiErrorException)
        refusalOf(refusedAgain) == RefusalCode.STEP_MOVED_ON

        and:
        stepRows() == before

        where:
        decidedFirst | number
        false        | 2
        true         | 1
    }

    def "values waiting on the model the step names are reviewed by nobody in its place"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set reviewer_model = 'general', reviewer_mode = 'ordinary' where workflow_step_id = ?::uuid")
                .params(SUMMARISE).update()
        waitingOnReview()
        def before = stepRows()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == RefusalCode.REVIEW_NOT_A_PERSONS

        and:
        stepRows() == before
    }

    def "a stopped run takes no review until it is opened again"() {
        given:
        waitingOnReview()
        RunRows.stopped(store, RUN, CAT)
        def before = stepRows()

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == RefusalCode.RUN_STOPPED

        and:
        stepRows() == before
    }

    def "a review of values produced before what the step runs was stopped goes on"() {
        given:
        waitingOnReview()
        store.stopped(SUMMARISE_QUESTION, FIRST_STEWARD)

        when:
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())

        then:
        positionOf(SUMMARISE) instanceof StepPosition.Done
        store.count("select count(*) from reviews") == 1
    }

    def "an act on a step no version of the run holds is refused as no step, writing nothing"() {
        given:
        waitingOnReview()
        def before = stepRows()

        when:
        acts.review(groupId(GROUP), runId(), stepId("00000009-0000-4000-8000-000000000e09"), 1, ANN_USER, assured())

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == RefusalCode.STEP_NOT_IN_VIEW

        and:
        stepRows() == before
    }

    def "an act on a run the caller may not read is refused as no run, whatever they hold"() {
        given:
        started(ANNS_RUN, ANN)
        def before = stepRows()

        when:
        acts.askAgain(groupId(GROUP), runId(ANNS_RUN), stepId(SUMMARISE), 2, CAT_USER)

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == RefusalCode.RUN_NOT_IN_VIEW

        and:
        stepRows() == before
    }

    def "asking again after a refusal makes the next try, asked by the caller of whoever the step names"() {
        given:
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER)

        then:
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String, "2 person ${CAT} open" as String]
        with(positionOf(SUMMARISE) as StepPosition.Owed) {
            number() == 2
            open()
        }

        and: "taking the ticket again, and nothing asked of the next step"
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from production_inputs where binding_id = ?::uuid", TicketWorkflow.TICKET_IN) == 2
        triesOf(CONFIRM) == []
    }

    def "asking again a step whose tries are spent makes one try beyond them, the step no longer failed"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = 1 where workflow_step_id = ?::uuid").params(SUMMARISE).update()
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER)

        then:
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String, "2 person ${CAT} open" as String]
        with(positionOf(SUMMARISE) as StepPosition.Owed) {
            number() == 2
            beyond()
        }
    }

    def "asking again is refused, writing nothing, where the step does not owe that try as asked"() {
        given:
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())
        if (stopped == "run") {
            RunRows.stopped(store, RUN, CAT)
        }
        if (stopped == "question") {
            store.stopped(SUMMARISE_QUESTION, FIRST_STEWARD)
        }
        def before = stepRows()

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), number, CAT_USER)

        then:
        def refusedAgain = thrown(ApiErrorException)
        refusalOf(refusedAgain) == refusal

        and:
        stepRows() == before

        where:
        stopped    | number || refusal
        "nothing"  | 3      || RefusalCode.STEP_MOVED_ON
        "nothing"  | 1      || RefusalCode.STEP_MOVED_ON
        "run"      | 2      || RefusalCode.RUN_STOPPED
        "question" | 2      || RefusalCode.ENTRY_STOPPED
    }

    private static JsonValue.JsonObject filled(Map<String, Object> values) {
        new JsonValue.JsonObject(values.collect { name, value ->
            new JsonValue.JsonMember(name, value == null ? new JsonValue.JsonNull() : new JsonValue.JsonString(value as String))
        })
    }

    private List<String> valuesOf(String step) {
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("""
                select try.try_number || ' ' || field.name || ' ' || coalesce(value.value::text, '-') || ' '
                       || value.needs_review
                  from production_values value
                  join productions try on try.production_id = value.production_id
                  join run_steps held on held.run_step_id = try.run_step_id
                  join declaration_fields field on field.declaration_field_id = value.declaration_field_id
                 where held.workflow_step_id = ?::uuid order by try.try_number, field.position
                """, step)
    }

    def "asking again a try asked already, still waiting on an answer, is refused as the step having moved on"() {
        given:
        started()
        def before = stepRows()

        when:
        acts.askAgain(groupId(GROUP), runId(), stepId(SUMMARISE), 1, CAT_USER)

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == RefusalCode.STEP_MOVED_ON

        and:
        stepRows() == before
    }

    def "answering the try asked ends it as the caller's, with why they said it, each value against its own field"() {
        given:
        started()

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 1, CAT_USER, filled([summary: "A printer fire."]),
                "Read it twice.")

        then:
        store.texts("select ended_by || ' ' || ended_by_kind || ' ' || explanation from productions") ==
                ["${CAT} person Read it twice." as String]
        valuesOf(SUMMARISE) == ['1 summary "A printer fire." true']
        positionOf(SUMMARISE) instanceof StepPosition.AwaitingReview

        and: "no try made beside it, and the next step not asked while the value waits"
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String]
        triesOf(CONFIRM) == []
    }

    def "values declared to stand as given stand at once, and the run goes on to its end"() {
        given:
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())

        when:
        acts.answer(groupId(GROUP), runId(), stepId(CONFIRM), 1, CAT_USER, filled([approved: "true", note: ""]), "Checked.")

        then:
        valuesOf(CONFIRM) == ["1 approved true false", "1 note - false"]
        positionOf(CONFIRM) instanceof StepPosition.Done
        store.count("select count(*) from productions where ended_at is null") == 0
    }

    def "answering here after a refusal makes the next try the caller's own and fills it, whoever made the last"() {
        given:
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER, filled([summary: "A fire in the hall."]),
                "Longer now.")

        then:
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String, "2 person ${CAT} ended" as String]
        valuesOf(SUMMARISE) == ['1 summary "A printer fire." true', '2 summary "A fire in the hall." true']
        with(positionOf(SUMMARISE) as StepPosition.AwaitingReview) {
            number() == 2
        }

        and: "the new try took the ticket again"
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from production_inputs where binding_id = ?::uuid", TicketWorkflow.TICKET_IN) == 2
    }

    def "answering a step whose tries are spent is a try beyond them, and the step is no longer failed"() {
        given:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_steps set tries = 1 where workflow_step_id = ?::uuid").params(SUMMARISE).update()
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER, filled([summary: "A fire."]), "Again.")

        then:
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String, "2 person ${CAT} ended" as String]
        positionOf(SUMMARISE) instanceof StepPosition.AwaitingReview
    }

    def "values that do not fit what the step gives back are refused, spending no try and writing nothing"() {
        given:
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())
        def before = stepRows()

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER, sent, "Again.")

        then:
        def refusedAnswer = thrown(ApiErrorException)
        refusalOf(refusedAnswer) == refusal

        and:
        stepRows() == before

        where:
        sent                                                     || refusal
        filled([summary: ""])                                    || RefusalCode.VALUE_DOES_NOT_FIT
        filled([summary: "x" * 1001])                            || RefusalCode.VALUE_DOES_NOT_FIT
        filled([:])                                              || RefusalCode.BODY_UNUSABLE
        filled([summary: "A fire.", note: "More."])              || RefusalCode.BODY_UNUSABLE
        new JsonValue.JsonString("A fire.")                      || RefusalCode.BODY_UNUSABLE
    }

    def "a hold left behind by a stop let go with nothing going on after it is released as an answer lands, and the answer is taken"() {
        given:
        store.stopped(TicketWorkflow.CONFIRM_QUESTION, FIRST_STEWARD)
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("update entry_stops set let_go_at = now(), let_go_by = ?::uuid").params(FIRST_STEWARD).update()

        expect: "the hold still stands, nothing having gone on after the stop went, yet the try it held reads as owed"
        store.count("select count(*) from run_step_holds where released_at is null") == 1
        with(positionOf(CONFIRM) as StepPosition.Owed) {
            number() == 1
            open()
            !beyond()
        }

        when:
        acts.answer(groupId(GROUP), runId(), stepId(CONFIRM), 1, CAT_USER, filled([approved: "true", note: ""]), "Checked.")

        then: "the hold released by the system, the try the stop held made, and answered"
        store.texts("select released_by || ' ' || released_by_kind from run_step_holds") == ["${SYSTEM} system" as String]
        triesOf(CONFIRM) == ["1 person ${SYSTEM} ended" as String]
        positionOf(CONFIRM) instanceof StepPosition.Done
    }

    def "each value that does not fit is named with why it does not, and a value that fits is not named"() {
        given:
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, refused())

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), 2, CAT_USER, filled([summary: "x" * 1001]), "Again.")

        then:
        def refusedAnswer = thrown(ValueProblemsRefusal)
        refusedAnswer.problems() == [new FillProblem(new FillPath([new FillPath.Named(new FieldName("summary"))]),
                FillReason.TOO_LONG)]
    }

    def "an answer is refused, writing nothing, where the step does not owe that try as answered"() {
        given:
        started()
        if (stopped == "run") {
            RunRows.stopped(store, RUN, CAT)
        }
        if (stopped == "question") {
            store.stopped(SUMMARISE_QUESTION, FIRST_STEWARD)
        }
        def before = stepRows()

        when:
        acts.answer(groupId(GROUP), runId(), stepId(SUMMARISE), number, CAT_USER, filled([summary: "A fire."]), "Why.")

        then:
        def refused = thrown(ApiErrorException)
        refusalOf(refused) == refusal

        and:
        stepRows() == before

        where:
        stopped    | number || refusal
        "nothing"  | 2      || RefusalCode.STEP_MOVED_ON
        "run"      | 1      || RefusalCode.RUN_STOPPED
        "question" | 1      || RefusalCode.ENTRY_STOPPED
    }
}
