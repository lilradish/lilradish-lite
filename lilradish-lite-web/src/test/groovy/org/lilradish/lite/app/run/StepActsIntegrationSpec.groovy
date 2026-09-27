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
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.app.codestep.CodeRuns
import org.lilradish.lite.app.filling.ValueProblemsRefusal
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.run.fixture.StepRows
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.failure.RefusalCode
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
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.lilradish.lite.testutil.library.DeployedModels
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import org.slf4j.LoggerFactory
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A person reviewing what waits on them and asking for a step's next try, on a real server running the real
 * baseline, one act at a time; each lands as the caller's act, and the run goes on as far as it goes by itself.
 */
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
        snapshots = new RunSnapshots(session, CodeStepsHeld.NONE)
        executor = EngineExecutors.of(store.database)
        def writes = new EngineWrites(session)
        def ceilings = new CeilingReach(session, DeployedModels.HELD, writes)
        engine = new RunEngine(session, store.transactions(), tree, snapshots, writes, executor,
                new EngineCalls(new ScriptedModelCalls(), store.transactions(), tree, writes, ceilings),
                new EngineCodes(new CodeRuns(CodeStepsHeld.NONE), store.transactions(), tree, snapshots, writes, executor),
                DeployedModels.HELD, ceilings, new GroupRoles(session))
        acts = new StepActs(session, store.transactions(), new GroupRoles(session), tree, snapshots, writes, engine)
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

    /** Summarise asked and answered by Cat, its summary waiting on a review. */
    private void waitingOnReview() {
        started()
        StepRows.answered(store, SUMMARISE, CAT, "Read it twice.", [(SUMMARY): '"A printer fire."'])
    }

    private StepPosition positionOf(String step, String run = RUN) {
        def read = snapshots.asRead(groupId(GROUP), runId(run))
        StepPositions.of(read, read.step(stepId(step)).orElseThrow())
    }

    private List<String> triesOf(String step) {
        store.texts("""
                select try.try_number || ' ' || try.producer || ' ' || try.created_by || ' '
                       || case when try.ended_at is null then 'open' else 'ended' end
                  from productions try join run_steps held on held.run_step_id = try.run_step_id
                 where held.workflow_step_id = ?::uuid order by try.try_number
                """, step)
    }

    /** Every row an act on a step can write, as one string per table. */
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
        store.texts("""
                select taken.binding_id || ' ' || value.value::text
                  from production_inputs taken
                  join production_values value on value.production_value_id = taken.source_production_value_id
                """) == ["${SUMMARY_IN} \"A printer fire.\"" as String]

        and: "and summarise asked nothing more"
        triesOf(SUMMARISE) == ["1 person ${SYSTEM} ended" as String]
    }

    /** The act has committed by the time the run goes on, so what goes wrong after it is the run's, not the act's. */
    def "a review that lands is answered as landed, and a run that then cannot go on by itself is logged where it stopped"() {
        given:
        waitingOnReview()
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
        store.count("select count(*) from production_inputs where binding_id = ?::uuid", TicketWorkflow.TICKET_IN) == 2
        triesOf(CONFIRM) == []
    }

    def "asking again a step whose tries are spent makes one try beyond them, the step no longer failed"() {
        given:
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
        store.count("select count(*) from production_inputs where binding_id = ?::uuid", TicketWorkflow.TICKET_IN) == 2
    }

    def "answering a step whose tries are spent is a try beyond them, and the step is no longer failed"() {
        given:
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

    /** A let-go whose going on never ran leaves its hold behind; the next act on the run releases it in place. */
    def "a hold left behind by a stop let go with nothing going on after it is released as an answer lands, and the answer is taken"() {
        given:
        store.stopped(TicketWorkflow.CONFIRM_QUESTION, FIRST_STEWARD)
        waitingOnReview()
        acts.review(groupId(GROUP), runId(), stepId(SUMMARISE), 1, ANN_USER, assured())
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
