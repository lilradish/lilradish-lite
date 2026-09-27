package org.lilradish.lite.app.run

import static org.lilradish.lite.domain.declaration.FieldStanding.ABOVE_CONFIDENCE
import static org.lilradish.lite.domain.declaration.FieldStanding.ALWAYS
import static org.lilradish.lite.domain.declaration.FieldStanding.NEVER
import static org.lilradish.lite.domain.run.fixture.Runs.MODEL
import static org.lilradish.lite.domain.run.fixture.Runs.PERSON
import static org.lilradish.lite.domain.run.fixture.Runs.VERSION
import static org.lilradish.lite.domain.run.fixture.Runs.askedAt
import static org.lilradish.lite.domain.run.fixture.Runs.asking
import static org.lilradish.lite.domain.run.fixture.Runs.assured
import static org.lilradish.lite.domain.run.fixture.Runs.attemptId
import static org.lilradish.lite.domain.run.fixture.Runs.codeStep
import static org.lilradish.lite.domain.run.fixture.Runs.endedAt
import static org.lilradish.lite.domain.run.fixture.Runs.gives
import static org.lilradish.lite.domain.run.fixture.Runs.json
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.lost
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.model
import static org.lilradish.lite.domain.run.fixture.Runs.modelReview
import static org.lilradish.lite.domain.run.fixture.Runs.open
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.personReview
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.sentToReview
import static org.lilradish.lite.domain.run.fixture.Runs.started
import static org.lilradish.lite.domain.run.fixture.Runs.stepId
import static org.lilradish.lite.domain.run.fixture.Runs.stop
import static org.lilradish.lite.domain.run.fixture.Runs.takes
import static org.lilradish.lite.domain.run.fixture.Runs.text
import static org.lilradish.lite.domain.run.fixture.Runs.tidy
import static org.lilradish.lite.domain.run.fixture.Runs.tryId
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted
import static org.lilradish.lite.domain.run.fixture.Runs.valueId
import static org.lilradish.lite.domain.run.fixture.Runs.workflowStep
import static org.lilradish.lite.domain.run.fixture.Runs.yielded

import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.inference.ModelCallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.AttemptRecord
import org.lilradish.lite.domain.run.CallRecord
import org.lilradish.lite.domain.run.FailureRecord
import org.lilradish.lite.domain.run.HoldRecord
import org.lilradish.lite.domain.run.InputRecord
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.PlannedStep
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.RunSnapshot
import org.lilradish.lite.domain.run.RunStepFailureId
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.TryLostReason
import org.lilradish.lite.domain.run.TryRecord
import org.lilradish.lite.domain.run.ValueRecord
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification
import tools.jackson.databind.node.JsonNodeFactory

/**
 * One read of a run's steps as its reader is answered, shaped from what was read and nothing else. What a model
 * said is held back from a reader the permissions they hold do not let read it, wherever it is read again.
 */
class StepReadingSpec extends Specification {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance

    /** What reading what a model said turns on, held or not, beside every other permission a step's page reads. */
    static final Set<GroupPermission> READS_MODELS = EnumSet.of(GroupPermission.READ_ALL_RUNS,
            GroupPermission.ANSWER_STEP, GroupPermission.REVIEW_AT_GATE, GroupPermission.READ_INFERENCE_CONTENT)

    static final Set<GroupPermission> READS_NO_MODEL = READS_MODELS - GroupPermission.READ_INFERENCE_CONTENT

    static final ValueRecord FIRST_GO = new ValueRecord(valueId(1), "answer", json("First go."), 60, true)

    static final ValueRecord SECOND_GO = new ValueRecord(valueId(2), "answer", json("Second go."), 90, true)

    static final ValueRecord BY_HAND = new ValueRecord(valueId(3), "answer", json("By hand."), null, true)

    static final Binding TAKES_THE_ANSWER = new Binding(key(801), Pointer.parse("answer"),
            new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer")))

    /**
     * The model's question tried twice, the first refused by the model with its words and the second assured;
     * the next step asked of a person, taking what the second gave back; and a third a person answered, refused
     * by a person with theirs.
     */
    static final RunSnapshot RUNNING = run([
            started(question(1, model(), 3, MODEL), [
                    yielded(1, StepProducer.MODEL, [FIRST_GO], [modelReview(minutes(30), [refused(FIRST_GO)])]),
                    yielded(2, StepProducer.MODEL, [SECOND_GO], [modelReview(minutes(40), [assured(SECOND_GO)])])]),
            started(questionTaking(2, takes([text("answer")]), [TAKES_THE_ANSWER]), [
                    new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(5), null, null, null, null, null,
                            null, false, null, [], [], [new InputRecord(TAKES_THE_ANSWER.id(), valueId(2))], [], [])]),
            started(question(3, person(), 2), [
                    yielded(1, StepProducer.PERSON, [BY_HAND], [personReview(minutes(50), [refused(BY_HAND)])], PERSON)]),
    ])

    /** A model reviewing in a person's stead, as a step names one. */
    static final ModelChoice REVIEWER = new ModelChoice(new ModelName("small"), null)

    static final StepAnswers.WhoAnswer BY_REVIEWER = new StepAnswers.WhoAnswer("model", null, "small", null)

    static final StepAnswers.WhoAnswer BY_A_PERSON = new StepAnswers.WhoAnswer("person", null, null, null)

    /** The fourth step, taking what the first three gave back: a code step's, a question's and a workflow's. */
    static final PlannedStep TAKES_FROM_EACH = questionTaking(4,
            takes([text("from_code"), text("from_question"), text("from_workflow")]),
            [takingAnswerOf(811, "from_code", 1), takingAnswerOf(812, "from_question", 2),
             takingAnswerOf(813, "from_workflow", 3)])

    /** The second step, taking what the first gave back, a code step's. */
    static final PlannedStep TAKES_FROM_CODE = questionTaking(2, takes([text("from_code")]),
            [takingAnswerOf(821, "from_code", 1)])

    private static StepReading reading(Set<GroupPermission> permitted) {
        reading(RUNNING, permitted)
    }

    private static StepReading reading(RunSnapshot run, Set<GroupPermission> permitted,
                                       Map<ProductionId, StepReading.LostRecord> lost = [:],
                                       Map<ProductionId, List<StepReading.TurnawayRecord>> turnedAway = [:]) {
        new StepReading(run, 7, true, permitted, null, Optional.empty(), [:], [:], lost, turnedAway)
    }

    /** A try of a model's step asked and sent on {@code attempts}, each with its one call, ended as {@code lost}. */
    private static TryRecord sent(int number, TryLostReason lost, List<Integer> attempts) {
        new TryRecord(tryId(number), number, StepProducer.MODEL, null, askedAt(number),
                lost == null ? null : endedAt(number), null, null, lost, null, null, false, null, [], [], [],
                attempts.collect { new AttemptRecord(attemptId(it), ModelCallPurpose.PRODUCE, false, null) },
                attempts.collect {
                    new CallRecord(new ModelCallId(key(1100 + it)), attemptId(it), outcome(lost), false)
                })
    }

    private static ModelCallOutcome outcome(TryLostReason lost) {
        switch (lost) {
            case null: return ModelCallOutcome.TURNED_AWAY
            case TryLostReason.DID_NOT_FIT: return ModelCallOutcome.CAME_BACK
            case TryLostReason.ERRORED: return ModelCallOutcome.ERRORED
            default: return ModelCallOutcome.NOTHING_CAME_BACK
        }
    }

    /** A first-level field given back, standing as {@code standing} says, above 80 where it is above one. */
    private static Field givesStanding(FieldStanding standing, String name = "answer") {
        new Field(new FieldName(name), null, null, new FieldShape.Text(200), new HowMany.One(),
                new Demand.Stands(true, standing, standing == ABOVE_CONFIDENCE ? 80 : null))
    }

    /** The first step, a question giving back one field for each of {@code standings}, standing as it says. */
    private static PlannedStep giving(List<FieldStanding> standings, Producer producer, ModelChoice reviewer = null) {
        def fields = standings.withIndex().collect { standing, index -> givesStanding(standing, "field_" + index) }
        new PlannedStep(stepId(1), 1, new StepId("step_1"), asking(1, takes([]), gives(fields)), producer, 2, reviewer,
                [])
    }

    private static Producer researching() {
        new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), false)
    }

    private static FailureRecord notHeldOn(int number, ModelCallPurpose purpose) {
        new FailureRecord(new RunStepFailureId(key(1101)), RunStepFailureReason.MODEL_NOT_DEPLOYED, tryId(number),
                purpose, minutes(15), false)
    }

    private static FailureRecord uncuttableOn(int number) {
        new FailureRecord(new RunStepFailureId(key(1102)), RunStepFailureReason.UNCUTTABLE_LENGTH, tryId(number),
                ModelCallPurpose.PRODUCE, minutes(15), false)
    }

    private static Binding takingAnswerOf(int numbered, String target, int order) {
        new Binding(key(numbered), Pointer.parse(target),
                new BindingSource.StepOutput(stepId(order).value(), Pointer.parse("answer")))
    }

    def "a run names why a model lost a try only where one did not fit or went wrong"() {
        expect:
        StepReading.namesLost(run([started(planned, [endedAs])])) == names

        where:
        planned                   | endedAs                                       || names
        question(1, model(), 3)   | sent(1, TryLostReason.DID_NOT_FIT, [1])       || true
        question(1, model(), 3)   | sent(1, TryLostReason.ERRORED, [1])           || true
        question(1, model(), 3)   | sent(1, TryLostReason.NOTHING_CAME_BACK, [1]) || false
        question(1, model(), 3)   | sent(1, null, [1])                            || false
        codeStep(1, 1)            | lost(1, StepProducer.CODE)                    || false
    }

    def "a run names turnaways to read only where a try of it sent a call"() {
        expect:
        StepReading.namesCalls(run([started(question(1, model(), 3), tries)])) == names

        where:
        tries                                    || names
        [sent(1, null, [1])]                     || true
        [sent(1, TryLostReason.ERRORED, [])]     || false
        [open(1, StepProducer.MODEL)]            || false
        []                                       || false
    }

    def "every value a model gave back, as a step gave it back, is withheld from a reader who may not read what a model said"() {
        when:
        def row = reading(permitted).steps().steps()[0]

        then:
        row.gaveBack() == [new StepAnswers.ShownValueAnswer("answer", value, withheld, "stands", null)]

        where:
        permitted      || value                          | withheld
        READS_NO_MODEL || null                           | true
        READS_MODELS   || NODES.stringNode("Second go.") | null
    }

    def "each value a model gave back, how sure it was, and the words it refused one with are withheld, and say so"() {
        when:
        def tries = reading(READS_NO_MODEL).step(stepId(1)).triesMade()

        then: "the first try's value and confidence held back, and the model's words refusing it too"
        tries[0].values() == [new StepAnswers.TriedValueAnswer("answer", null, true, "refused", null,
                new StepAnswers.DecisionAnswer("refused", null, true), null)]

        and: "the second's assured with no words to hold back, so none is said to be held back"
        tries[1].values() == [new StepAnswers.TriedValueAnswer("answer", null, true, "stands", null,
                new StepAnswers.DecisionAnswer("assured", null, null), null)]
    }

    def "a reader who may read what a model said reads every value, how sure it was, and the words it was refused with"() {
        when:
        def tries = reading(READS_MODELS).step(stepId(1)).triesMade()

        then:
        tries*.values() == [
                [new StepAnswers.TriedValueAnswer("answer", NODES.stringNode("First go."), null, "refused", 60,
                        new StepAnswers.DecisionAnswer("refused", "Not what was asked.", null), null)],
                [new StepAnswers.TriedValueAnswer("answer", NODES.stringNode("Second go."), null, "stands", 90,
                        new StepAnswers.DecisionAnswer("assured", null, null), null)]]
    }

    def "what a model gave back is withheld where it stands, and where it went into the next step"() {
        when:
        def cameOut = reading(permitted).step(stepId(1)).cameOut()
        def wentIn = reading(permitted).step(stepId(2)).wentIn()

        then:
        cameOut == [new StepAnswers.CameOutAnswer("answer", new StepAnswers.StandingAnswer(2, value, withheld, null),
                "stands")]
        wentIn == [new StepAnswers.WentInAnswer("answer",
                new StepAnswers.FromAnswer("step", "answer", stepId(1).value(), "step_1", key(201), null), value,
                withheld, null)]

        where:
        permitted      || value                          | withheld
        READS_NO_MODEL || null                           | true
        READS_MODELS   || NODES.stringNode("Second go.") | null
    }

    /**
     * A try's calls are read apart; the step's is theirs between them, one call not come back leaving all unknown,
     * and one the model did not count leaving the whole measured here.
     */
    def "a step calling a model says what its tries spent between them and each apart, and one calling none says only that"() {
        given:
        def spent = [(tryId(1)): new RunBudget.Spend(100, 40, false, false),
                     (tryId(2)): new RunBudget.Spend(30, 0, true, true)]
        def read = new StepReading(RUNNING, 7, true, READS_MODELS, null, Optional.empty(), [:], spent, [:], [:])

        when:
        def rows = read.steps().steps()
        def tries = read.step(stepId(1)).triesMade()

        then:
        rows[0].cost() == new StepAnswers.CostAnswer(true, "130", "40", "170", true, true)
        tries*.cost() == [new StepAnswers.CostAnswer(true, "100", "40", "140", false, null),
                          new StepAnswers.CostAnswer(true, "30", "0", "30", true, true)]

        and: "a step no model produces or reviews counts none of it, whatever its tries are keyed by"
        rows[2].cost() == new StepAnswers.CostAnswer(false, null, null, null, null, null)
        read.step(stepId(3)).triesMade()*.cost() == [new StepAnswers.CostAnswer(false, null, null, null, null, null)]
    }

    def "what a person gave, and the words a person refused it with, are held back from nobody"() {
        when:
        def tries = reading(READS_NO_MODEL).step(stepId(3)).triesMade()

        then:
        tries*.values() == [[new StepAnswers.TriedValueAnswer("answer", NODES.stringNode("By hand."), null, "refused",
                null, new StepAnswers.DecisionAnswer("refused", "Not what was asked.", null), null)]]
    }

    def "a step's page says when to read it again as the run's steps do, only while the run is running"() {
        given:
        def read = reading(run([started(question(1, person(), 2), [])], stopped), READS_MODELS)

        when:
        def page = read.step(stepId(1))

        then:
        page.rereadAfterSeconds() == reread
        page.rereadAfterSeconds() == read.steps().rereadAfterSeconds()
        page.run().state() == state

        where:
        stopped || reread                        | state
        false   || RunSteps.REREAD_AFTER_SECONDS | "running"
        true    || null                          | "stopped"
    }

    /** Every turnaway of the try it is held on is read, oldest first, an earlier attempt's as much as the hold's. */
    def "a step held back on its model says whether what may be spent is used up, and each time a call to produce its try was turned away"() {
        given:
        def held = run([started(question(1, model(), 3), [sent(1, null, [2, 1])],
                new HoldRecord(reason, minutes(20), attemptId(1), spentUp))])
        def turned = [(tryId(1)): [new StepReading.TurnawayRecord(attemptId(2), minutes(11), "Busy.", false, true),
                                   new StepReading.TurnawayRecord(attemptId(1), minutes(12), "Spent.", true, false)]]

        when:
        def whereabouts = reading(held, permitted, [:], turned).steps().steps()[0].where()

        then:
        whereabouts.kind() == "held_back"
        whereabouts.reason() == reason.published()
        whereabouts.spentUp() == shownSpentUp
        whereabouts.turnedAway() == turnedAway

        and: "nothing else a step's whereabouts may say"
        [whereabouts.model(), whereabouts.mode(), whereabouts.reviewing(), whereabouts.number(),
         whereabouts.values(), whereabouts.open(), whereabouts.beyond(), whereabouts.on(),
         whereabouts.stopped(), whereabouts.review()].every { it == null }

        where:
        reason                        | spentUp | permitted      || shownSpentUp | turnedAway
        RunStepHoldReason.TURNED_AWAY | true    | READS_MODELS   || true         | [new StepAnswers.TurnawayAnswer(minutes(11).toString(), "Busy.", false, null, true), new StepAnswers.TurnawayAnswer(minutes(12).toString(), "Spent.", true, null, false)]
        RunStepHoldReason.TURNED_AWAY | false   | READS_MODELS   || null         | [new StepAnswers.TurnawayAnswer(minutes(11).toString(), "Busy.", false, null, true), new StepAnswers.TurnawayAnswer(minutes(12).toString(), "Spent.", true, null, false)]
        RunStepHoldReason.TURNED_AWAY | true    | READS_NO_MODEL || true         | [new StepAnswers.TurnawayAnswer(minutes(11).toString(), null, null, true, true), new StepAnswers.TurnawayAnswer(minutes(12).toString(), null, null, true, false)]
        RunStepHoldReason.TOO_LONG    | false   | READS_MODELS   || null         | null
    }

    /** Whether it was to produce or to review is read off the failure, which says what it was sending for. */
    def "a step failed for a model not held says which model, in which mode, and whether it was to produce or to review"() {
        when:
        def whereabouts = reading(run([started(planned, tries, null, failures)]), READS_MODELS)
                .steps().steps()[0].where()

        then:
        whereabouts.kind() == "failed"
        whereabouts.reason() == reason
        whereabouts.model() == modelNamed
        whereabouts.mode() == mode
        whereabouts.reviewing() == reviewing

        and: "nothing a hold says"
        whereabouts.spentUp() == null
        whereabouts.turnedAway() == null
        whereabouts.stopped() == null

        where:
        planned                                  | tries                                         | failures                                      || reason               | modelNamed | mode       | reviewing
        question(1, model(), 3)                  | [open(1, StepProducer.MODEL)]                 | [notHeldOn(1, ModelCallPurpose.PRODUCE)]      || "model_not_deployed" | "general"  | null       | null
        question(1, researching(), 3)            | [open(1, StepProducer.MODEL)]                 | [notHeldOn(1, ModelCallPurpose.PRODUCE)]      || "model_not_deployed" | "general"  | "research" | null
        question(1, person(), 2, REVIEWER)       | [yielded(1, StepProducer.PERSON, [BY_HAND])]  | [notHeldOn(1, ModelCallPurpose.REVIEW)]       || "model_not_deployed" | "small"    | null       | true
        question(1, researching(), 3, REVIEWER)  | [yielded(1, StepProducer.MODEL, [SECOND_GO])] | [notHeldOn(1, ModelCallPurpose.REVIEW)]       || "model_not_deployed" | "small"    | null       | true
        question(1, model(), 3)                  | [open(1, StepProducer.MODEL)]                 | [uncuttableOn(1)]                             || "uncuttable_length"  | null       | null       | null
        question(1, model(), 1)                  | [sent(1, TryLostReason.ERRORED, [1])]         | []                                            || "tries_spent"        | null       | null       | null
    }

    /** The stop is what keeps Try sending from it, so it is read beside the reason the step is held back or failed. */
    def "a model's step held back or failed while what it runs is stopped names that stop, its own reason unchanged, and none where nothing is"() {
        given:
        def held = run([started(question(1, model(), 3), tries, hold, failures, stopped)])
        def turned = [(tryId(1)): [new StepReading.TurnawayRecord(attemptId(1), minutes(12), "Spent.", true, false)]]

        when:
        def whereabouts = reading(held, READS_MODELS, [:], turned).steps().steps()[0].where()

        then:
        whereabouts.kind() == kind
        whereabouts.reason() == reason
        whereabouts.stopped() == shownStop
        whereabouts.turnedAway() == turnedAway

        where:
        tries                         | hold                                                                         | failures                                 | stopped           || kind        | reason               | shownStop                                                          | turnedAway
        [sent(1, null, [1])]          | new HoldRecord(RunStepHoldReason.TURNED_AWAY, minutes(20), attemptId(1), false) | []                                    | stop(minutes(25)) || "held_back" | "turned_away"        | new StepAnswers.StoppedAnswer("entry", null, minutes(25).toString()) | [new StepAnswers.TurnawayAnswer(minutes(12).toString(), "Spent.", true, null, false)]
        [sent(1, null, [1])]          | new HoldRecord(RunStepHoldReason.TURNED_AWAY, minutes(20), attemptId(1), false) | []                                    | null              || "held_back" | "turned_away"        | null                                                               | [new StepAnswers.TurnawayAnswer(minutes(12).toString(), "Spent.", true, null, false)]
        [sent(1, null, [1])]          | new HoldRecord(RunStepHoldReason.TOO_LONG, minutes(20), attemptId(1), false)    | []                                    | stop(minutes(25)) || "held_back" | "too_long"           | new StepAnswers.StoppedAnswer("entry", null, minutes(25).toString()) | null
        [open(1, StepProducer.MODEL)] | null                                                                         | [notHeldOn(1, ModelCallPurpose.PRODUCE)] | stop(minutes(25)) || "failed"    | "model_not_deployed" | new StepAnswers.StoppedAnswer("entry", null, minutes(25).toString()) | null
        [open(1, StepProducer.MODEL)] | null                                                                         | [notHeldOn(1, ModelCallPurpose.PRODUCE)] | null              || "failed"    | "model_not_deployed" | null                                                               | null
        [open(1, StepProducer.MODEL)] | null                                                                         | [uncuttableOn(1)]                        | stop(minutes(25)) || "failed"    | "uncuttable_length"  | new StepAnswers.StoppedAnswer("entry", null, minutes(25).toString()) | null
    }

    /** A stop keeps only a production from being sent again, so what waits on a review is never read beside one. */
    def "a step whose review failed on its reviewer or was turned away names no stop, whether or not what it runs is stopped"() {
        given:
        def produced = yielded(1, StepProducer.PERSON, [BY_HAND])
        def tries = failures ? [produced] : [sentToReview(produced, false, ModelCallOutcome.TURNED_AWAY, spentUp)]
        def reviewed = run([started(question(1, person(), 2, REVIEWER), tries, null, failures, stopped)])

        when:
        def whereabouts = reading(reviewed, READS_MODELS).steps().steps()[0].where()

        then:
        whereabouts.kind() == kind
        whereabouts.review() == review
        whereabouts.spentUp() == shownSpentUp
        whereabouts.stopped() == null

        where:
        failures                                | spentUp | stopped           || kind                | review        | shownSpentUp
        [notHeldOn(1, ModelCallPurpose.REVIEW)] | false   | stop(minutes(25)) || "failed"            | null          | null
        [notHeldOn(1, ModelCallPurpose.REVIEW)] | false   | null              || "failed"            | null          | null
        []                                      | false   | stop(minutes(25)) || "waiting_on_review" | "turned_away" | null
        []                                      | true    | stop(minutes(25)) || "waiting_on_review" | "turned_away" | true
        []                                      | false   | null              || "waiting_on_review" | "turned_away" | null
    }

    /** Standing above a confidence waits on a person as never standing does, since a person's production has none. */
    def "a step is reviewed by the model it names, otherwise by a person where any field may wait, and by nobody where all stand"() {
        when:
        def row = reading(run([unstarted(planned)]), READS_MODELS).steps().steps()[0]

        then:
        row.reviewer() == reviewer

        and: "only a model reviewing, or producing, has the step call one"
        row.cost().callsAModel() == callsAModel

        where:
        planned                                                  || reviewer    | callsAModel
        giving([ALWAYS], person(), REVIEWER)                     || BY_REVIEWER | true
        giving([NEVER], person())                                || BY_A_PERSON | false
        giving([ABOVE_CONFIDENCE], model())                      || BY_A_PERSON | true
        giving([ALWAYS, NEVER], person())                        || BY_A_PERSON | false
        giving([ALWAYS, ALWAYS], model())                        || null        | true
        codeStep(1, 1, tidy(false, [], [givesStanding(NEVER)]))  || BY_A_PERSON | false
        codeStep(1, 1)                                           || null        | false
        codeStep(1, 1, null)                                     || null        | false
        workflowStep(1)                                          || null        | false
    }

    def "a try lost for not fitting says which way, and one gone wrong says what its call said, to a reader who may read it"() {
        given:
        def spentAll = run([started(question(1, model(), 3), [sent(1, TryLostReason.DID_NOT_FIT, [1]),
                                                              sent(2, TryLostReason.ERRORED, [2]),
                                                              sent(3, TryLostReason.NOTHING_CAME_BACK, [3])])])
        def why = [(tryId(1)): new StepReading.LostRecord(DidNotFitReason.CUT_OFF, null, false),
                   (tryId(2)): new StepReading.LostRecord(null, "Upstream failed.", true)]

        when:
        def tries = reading(spentAll, permitted, why).step(stepId(1)).triesMade()

        then:
        tries*.ended() == ["did_not_fit", "errored", "nothing_came_back"]
        tries*.didNotFit() == ["cut_off", null, null]
        tries*.wentWrong() == [null, wentWrong, null]

        and: "none of them turned away, the reader told of no turnaway"
        tries*.turnedAway() == [null, null, null]

        where:
        permitted      || wentWrong
        READS_MODELS   || new StepAnswers.WentWrongAnswer("Upstream failed.", true, null)
        READS_NO_MODEL || new StepAnswers.WentWrongAnswer(null, null, true)
    }

    /** What code said is not what a model said, so a reader who may read no model's words still reads it. */
    def "what went wrong in code is read whole by a reader who may not read what a model said, and says nothing of fitting"() {
        given:
        def wentWrongInCode = new TryRecord(tryId(1), 1, StepProducer.CODE, null, askedAt(1), endedAt(1), null, null,
                TryLostReason.ERRORED, null, "The mail server refused it.", false, null, [], [], [], [], [])

        when:
        def tries = reading(run([started(codeStep(1, 1), [wentWrongInCode])]), permitted).step(stepId(1)).triesMade()

        then:
        tries*.wentWrong() == [new StepAnswers.WentWrongAnswer("The mail server refused it.", false, null)]
        tries*.erroredFor() == [null]
        tries*.didNotFit() == [null]
        tries*.turnedAway() == [null]

        where:
        permitted << [READS_NO_MODEL, READS_MODELS]
    }

    /**
     * Said as a word the page puts in the reader's words, and read whole by anybody, being this system's; that what
     * came back was not kept is said only where the reason is found in something the code gave back.
     */
    def "a try of code gone wrong for this system's reason reads it, what it is about and reads it, and whether what came back was lost"() {
        given:
        def erred = new TryRecord(tryId(1), 1, StepProducer.CODE, null, askedAt(1), endedAt(1), null, null,
                TryLostReason.ERRORED, fault, null, false, returned, [], [], [], [], [])

        when:
        def tries = reading(run([started(codeStep(1, 1), [erred])]), permitted).step(stepId(1)).triesMade()

        then:
        tries*.erroredFor() == [answer]
        tries*.wentWrong() == [null]
        tries*.returned() == [returned]

        where:
        [permitted, fault, returned, answer] << [[READS_NO_MODEL, READS_MODELS], [
                [codeFault(CodeErrorReason.UNKEEPABLE, "receipt", null, null), null,
                 new StepAnswers.ErroredForAnswer("unkeepable", "receipt", null, null, true)],
                [codeFault(CodeErrorReason.TOO_LONG, "receipt", null, null), '{"receipt":"R-123456789"}',
                 new StepAnswers.ErroredForAnswer("too_long", "receipt", null, null, null)],
                [codeFault(CodeErrorReason.GAVE_NOTHING, null, null, null), null,
                 new StepAnswers.ErroredForAnswer("gave_nothing", null, null, null, null)],
                [codeFault(CodeErrorReason.NOT_DECLARED, null, "", null), '{"":1}',
                 new StepAnswers.ErroredForAnswer("not_declared", null, "", null, null)],
                [codeFault(CodeErrorReason.GIVES_OTHERWISE, "receipt", null, new CodeError.StepReads(stepId(2).value())),
                 null, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null,
                        new StepAnswers.ReadByAnswer(stepId(2).value(), null), null)],
                [codeFault(CodeErrorReason.GIVES_OTHERWISE, "receipt", null,
                        new CodeError.OutputReads([new FieldName("result"), new FieldName("receipt")])),
                 null, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null,
                        new StepAnswers.ReadByAnswer(null, "result.receipt"), null)],
        ]].combinations().collect { [it[0]] + it[1] }
    }

    private static CodeError.Fault codeFault(CodeErrorReason reason, String field, String member, CodeError.ReadBy readBy) {
        new CodeError.Fault(reason, field == null ? [] : Pointer.parse(field).names(), member, readBy)
    }

    def "every time a call to produce a try was turned away is on that try, oldest first, what the model said withheld as the rest of it is"() {
        given:
        def held = run([started(question(1, model(), 3), [sent(1, null, [2, 1])],
                new HoldRecord(RunStepHoldReason.TURNED_AWAY, minutes(20), attemptId(1), false))])
        def turned = [(tryId(1)): [new StepReading.TurnawayRecord(attemptId(2), minutes(11), null, false, true),
                                   new StepReading.TurnawayRecord(attemptId(1), minutes(12), "Too busy.", false, false)]]

        when:
        def tries = reading(held, permitted, [:], turned).step(stepId(1)).triesMade()

        then:
        tries*.turnedAway() == [[new StepAnswers.TurnawayAnswer(minutes(11).toString(), null, null, null, true),
                                 new StepAnswers.TurnawayAnswer(minutes(12).toString(), said, cut, withheld, false)]]

        and: "turned away every time it was sent, it has ended no way yet"
        tries*.ended() == ["open"]
        tries*.didNotFit() == [null]
        tries*.wentWrong() == [null]

        where:
        permitted      || said        | cut   | withheld
        READS_MODELS   || "Too busy." | false | null
        READS_NO_MODEL || null        | null  | true
    }

    def "an input taken from an earlier step names the version it pins, or the code step it runs, which the declarations hold by that"() {
        given:
        def read = reading(run([unstarted(codeStep(1, 1)), unstarted(question(2, person(), 1)),
                                unstarted(workflowStep(3)), unstarted(TAKES_FROM_EACH)]), READS_MODELS).steps()

        expect:
        read.steps()[3].takesFrom() == [
                new StepAnswers.TakesFromAnswer("from_code",
                        new StepAnswers.FromAnswer("step", "answer", stepId(1).value(), "step_1", null, "tidy")),
                new StepAnswers.TakesFromAnswer("from_question",
                        new StepAnswers.FromAnswer("step", "answer", stepId(2).value(), "step_2", key(202), null)),
                new StepAnswers.TakesFromAnswer("from_workflow",
                        new StepAnswers.FromAnswer("step", "answer", stepId(3).value(), "step_3", key(203), null))]

        and: "the code step declared by its name beside each version a question pins, and no workflow a step runs"
        read.declarations().keySet() as List == [VERSION.value().toString(), "tidy", key(202).toString(),
                                                 key(204).toString()]
        read.declarations()["tidy"].gives()*.name() == ["answer"]
        read.declarations()["tidy"].takes() == []
    }

    /** A field is read with its list's terms, so one the release pins and this group does not hold cannot be read. */
    def "a code step whose release pins a list not held here is named where an input comes from, and declared nowhere"() {
        given:
        def pinsAList = tidy(false, [], [new Field(new FieldName("answer"), null, null,
                new FieldShape.Term(new EntryVersionId(key(800))), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))])

        when:
        def read = reading(run([unstarted(codeStep(1, 1, pinsAList)), unstarted(TAKES_FROM_CODE)]),
                READS_MODELS).steps()

        then:
        read.steps()[1].takesFrom()[0].from().codeStep() == "tidy"
        !read.declarations().containsKey("tidy")
        read.declarations().keySet() as List == [VERSION.value().toString(), key(202).toString()]
    }
}
