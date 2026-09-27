package org.lilradish.lite.domain.run.fixture

import java.time.Instant
import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.codestep.CodeStepDeclaration
import org.lilradish.lite.domain.codestep.ReleasedCodeStep
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.inference.ModelCallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.run.AttemptRecord
import org.lilradish.lite.domain.run.CallRecord
import org.lilradish.lite.domain.run.DecisionRecord
import org.lilradish.lite.domain.run.FailureRecord
import org.lilradish.lite.domain.run.HoldRecord
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.PinnedEntry
import org.lilradish.lite.domain.run.PlannedStep
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.ProductionValueId
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.ReviewRecord
import org.lilradish.lite.domain.run.ReviewSending
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunSnapshot
import org.lilradish.lite.domain.run.RunStepFailureReason
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.RunStepId
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import org.lilradish.lite.domain.run.RunnableWorkflow
import org.lilradish.lite.domain.run.RunningOn
import org.lilradish.lite.domain.run.StepFailure
import org.lilradish.lite.domain.run.StepPosition
import org.lilradish.lite.domain.run.StepRuns
import org.lilradish.lite.domain.run.StepSnapshot
import org.lilradish.lite.domain.run.StopRecord
import org.lilradish.lite.domain.run.TryLostReason
import org.lilradish.lite.domain.run.TryRecord
import org.lilradish.lite.domain.run.ValueRecord
import org.lilradish.lite.domain.run.WaitsOn
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.domain.workflow.StepProducer

/** A run's values as the store hands them over, keyed and timed by fixed values so two builds of one are equal. */
final class Runs {

    static final Instant STARTED = Instant.parse("2026-03-02T09:00:00Z")

    static final SubjectId PERSON = new SubjectId(key(9001))

    /** Whoever stops an entry a run runs. */
    static final SubjectId STOPPER = new SubjectId(key(9002))

    static final ModelChoice MODEL = new ModelChoice(new ModelName("general"), null)

    static final RunId RUN = new RunId(key(1))

    static final GroupId GROUP = new GroupId(key(2))

    static final EntryVersionId VERSION = new EntryVersionId(key(3))

    private Runs() {}

    static UUID key(int number) {
        UUID.fromString(String.format("55550000-0000-4000-8000-%012d", number))
    }

    static Instant minutes(int later) {
        STARTED.plusSeconds(60L * later)
    }

    static JsonValue json(Object value) {
        switch (value) {
            case null: return new JsonValue.JsonNull()
            case JsonValue: return value as JsonValue
            case String: return new JsonValue.JsonString(value as String)
            case Boolean: return new JsonValue.JsonBoolean(value as boolean)
            case Number: return new JsonValue.JsonNumber(new BigDecimal(value.toString()))
            case Map: return new JsonValue.JsonObject((value as Map).collect { name, held ->
                new JsonValue.JsonMember(name as String, json(held))
            })
            case List: return new JsonValue.JsonArray((value as List).collect { json(it) })
            default: throw new IllegalArgumentException("No JSON for " + value.getClass())
        }
    }

    static Producer person() {
        new Producer.Person()
    }

    static Producer model() {
        new Producer.Model(MODEL, false)
    }

    static Producer code() {
        new Producer.Code()
    }

    static Field text(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.Text(200), new HowMany.One(), new Demand.Given(false))
    }

    static Field nested(String name, List<Field> fields) {
        new Field(new FieldName(name), null, null, new FieldShape.Nested(fields), new HowMany.One(), new Demand.Given(false))
    }

    static Field nestedMany(String name, List<Field> fields) {
        new Field(new FieldName(name), null, null, new FieldShape.Nested(fields), new HowMany.Many(10),
                new Demand.Given(false))
    }

    static Field standing(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.Text(200), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
    }

    static Declaration takes(List<Field> fields) {
        new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), fields)
    }

    static Declaration gives(List<Field> fields) {
        new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), fields)
    }

    static Declaration workflowHalf(DeclarationSide side) {
        new Declaration(side, Demands.ofWorkflow(side), [])
    }

    static PinnedEntry pinned(int order) {
        new PinnedEntry(new EntryId(key(100 + order)), new EntryName("Demo Sample"), new EntryVersionId(key(200 + order)), 1)
    }

    static WorkflowStepId stepId(int order) {
        new WorkflowStepId(key(400 + order))
    }

    static StepRuns.Question asking(int order, Declaration takes, Declaration gives) {
        new StepRuns.Question(pinned(order), new Instruction("Answer what is asked."), takes, gives,
                (0..<gives.fields().size()).collect { key(300 + 10 * order + it) }, [:])
    }

    static PlannedStep question(int order, Producer producer, int tries, ModelChoice reviewer = null) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order),
                asking(order, takes([]), gives([standing("answer")])), producer, tries, reviewer, [])
    }

    static PlannedStep questionTaking(int order, Declaration takes, List<Binding> bindings) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order),
                asking(order, takes, gives([standing("answer")])), person(), 1, null, bindings)
    }

    /** The code step {@code tidy} as a release declares it, taking and giving back the fields named. */
    static ReleasedCodeStep tidy(boolean mayRunAgain, List<Field> taken = [], List<Field> given = [standing("answer")]) {
        new ReleasedCodeStep(new CodeStepDeclaration(takes(taken), gives(given), mayRunAgain), [:])
    }

    /** A step running {@code tidy} as {@code released} declares it, none where the release holds no such code step. */
    static PlannedStep codeStep(int order, int tries, ReleasedCodeStep released = tidy(false),
                                Producer producer = code(), List<Binding> bindings = []) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), new StepRuns.Code("tidy", released),
                producer, tries, null, bindings)
    }

    static PlannedStep workflowStep(int order) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), new StepRuns.Workflow(pinned(order)),
                null, null, null, [])
    }

    static PlannedStep routeStep(int order) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), new StepRuns.Route(), null, null, null, [])
    }

    /** A stop in force since {@code since}, made by {@link #STOPPER}. */
    static StopRecord stop(Instant since) {
        new StopRecord(STOPPER, since)
    }

    static StepSnapshot unstarted(PlannedStep planned, StopRecord pinStopped = null) {
        new StepSnapshot(planned, pinStopped, null, null, [], [])
    }

    static StepSnapshot started(PlannedStep planned, List<TryRecord> tries, HoldRecord hold = null,
                                List<FailureRecord> failures = [], StopRecord pinStopped = null) {
        new StepSnapshot(planned, pinStopped, new RunStepId(key(500 + planned.order())), hold, failures, tries)
    }

    static ProductionId tryId(int number) {
        new ProductionId(key(600 + number))
    }

    static Instant askedAt(int number) {
        minutes(10 * number)
    }

    static Instant endedAt(int number) {
        minutes(10 * number + 5)
    }

    static TryRecord open(int number, StepProducer producer) {
        new TryRecord(tryId(number), number, producer, null, askedAt(number), null, null, null, null, null, null,
                false, null, [], [], [], [], [])
    }

    /** Gone wrong, code's try for the one reason that names nothing, as a try of code gone wrong always has one. */
    static TryRecord lost(int number, StepProducer producer) {
        new TryRecord(tryId(number), number, producer, null, askedAt(number), endedAt(number), null, null,
                TryLostReason.ERRORED,
                producer == StepProducer.CODE ? new CodeError.Fault(CodeErrorReason.SAID_NOTHING, [], null, null) : null,
                null, false, null, [], [], [], [], [])
    }

    static TryRecord yielded(int number, StepProducer producer, List<ValueRecord> values,
                             List<ReviewRecord> reviews = [], SubjectId endedBy = null) {
        new TryRecord(tryId(number), number, producer, null, askedAt(number), endedAt(number), endedBy, null, null,
                null, null, false, null, values, reviews, [], [], [])
    }

    static RunStepSendAttemptId attemptId(int number) {
        new RunStepSendAttemptId(key(1000 + number))
    }

    /** A hold since {@code since}, naming an attempt where its reason needs one, and its model not spent up. */
    static HoldRecord hold(RunStepHoldReason reason, Instant since) {
        boolean onAttempt = reason == RunStepHoldReason.TOO_LONG || reason == RunStepHoldReason.TURNED_AWAY
        new HoldRecord(reason, since, onAttempt ? attemptId(1) : null, false)
    }

    /** {@code newest} after as many tries before it as its number says, each ended with nothing given back. */
    static List<TryRecord> after(TryRecord newest) {
        List<TryRecord> tries = (1..<newest.number()).collect { yielded(it, newest.producer(), []) }
        tries + [newest]
    }

    static ProductionValueId valueId(int number) {
        new ProductionValueId(key(700 + number))
    }

    static ValueRecord value(int number, String field, boolean needsReview, Object held = "held " + number) {
        new ValueRecord(valueId(number), field, json(held), null, needsReview)
    }

    static DecisionRecord assured(ValueRecord value) {
        new DecisionRecord(value.id(), ReviewOutcome.ASSURED, null)
    }

    static DecisionRecord refused(ValueRecord value) {
        new DecisionRecord(value.id(), ReviewOutcome.REFUSED, "Not what was asked.")
    }

    static ReviewRecord personReview(Instant at, List<DecisionRecord> decisions) {
        new ReviewRecord(PERSON, at, false, null, null, decisions)
    }

    static ReviewRecord modelReview(Instant at, List<DecisionRecord> decisions) {
        new ReviewRecord(null, at, false, null, null, decisions)
    }

    static ReviewRecord lostReview(Instant at) {
        new ReviewRecord(null, at, false, TryLostReason.NOTHING_CAME_BACK, null, [])
    }

    static ReviewRecord lengthReview(Instant at, List<DecisionRecord> decisions) {
        new ReviewRecord(null, at, true, null, null, decisions)
    }

    /**
     * {@code aTry} with one attempt to review it: too long to send where {@code tooLong}, and otherwise sent in a call
     * ending as {@code outcome}, none while it is out.
     */
    static TryRecord sentToReview(TryRecord aTry, boolean tooLong, ModelCallOutcome outcome = null,
                                  boolean spentUp = false) {
        def attempt = new AttemptRecord(attemptId(50 + aTry.number()), ModelCallPurpose.REVIEW, tooLong, null)
        def calls = tooLong ? [] : [new CallRecord(new ModelCallId(key(1150 + aTry.number())), attempt.id(), outcome,
                spentUp)]
        new TryRecord(aTry.id(), aTry.number(), aTry.producer(), aTry.askedBy(), aTry.askedAt(), aTry.endedAt(),
                aTry.endedBy(), aTry.explanation(), aTry.lost(), aTry.fault(), aTry.lostDetail(), aTry.lostDetailCut(),
                aTry.returned(), aTry.values(), aTry.reviews(), aTry.inputs(), aTry.attempts() + [attempt],
                aTry.calls() + calls)
    }

    /** A position by what a reader would call it, every moment it names being {@link #STARTED}. */
    static StepPosition position(String named) {
        switch (named) {
            case "done": return new StepPosition.Done()
            case "not started": return new StepPosition.NotStarted()
            case "running code": return new StepPosition.Running(RunningOn.CODE)
            case "running a call": return new StepPosition.Running(RunningOn.CALL)
            case "running its next try": return new StepPosition.Running(RunningOn.NEXT_TRY)
            case "held back on a stop": return new StepPosition.HeldBack(RunStepHoldReason.ENTRY_STOPPED, STARTED,
                    new StepPosition.Owed(2, false, false, STARTED))
            case "held back on a stop before it was asked": return new StepPosition.HeldBack(
                    RunStepHoldReason.ENTRY_STOPPED, STARTED, null)
            case "held back too long": return new StepPosition.HeldBack(RunStepHoldReason.TOO_LONG, STARTED, null)
            case "held back, turned away": return new StepPosition.HeldBack(RunStepHoldReason.TURNED_AWAY, STARTED, null)
            case "held back, code step not held": return new StepPosition.HeldBack(
                    RunStepHoldReason.CODE_STEP_NOT_HELD, STARTED, null)
            case "awaiting a person's review": return awaiting([WaitsOn.REVIEW_AT_GATE], null)
            case "awaiting the model's review": return awaiting([WaitsOn.MODEL])
            case "awaiting the model's review, out": return awaiting([WaitsOn.MODEL], ReviewSending.OUT)
            case "awaiting the model's review, turned away": return awaiting([WaitsOn.MODEL], ReviewSending.TURNED_AWAY)
            case "awaiting a person's review in the model's place": return awaiting([WaitsOn.REVIEW_AT_GATE],
                    ReviewSending.TOO_LONG)
            case "awaiting a person's review and the model's": return awaiting([WaitsOn.REVIEW_AT_GATE, WaitsOn.MODEL])
            case "asked": return new StepPosition.Owed(1, true, false, STARTED)
            case "owed": return new StepPosition.Owed(2, false, false, STARTED)
            case "failed with its tries spent": return new StepPosition.Failed(new StepFailure.TriesSpent(1, 1), STARTED)
            case "failed as written down": return new StepPosition.Failed(
                    new StepFailure.Recorded(RunStepFailureReason.UNCUTTABLE_LENGTH, ModelCallPurpose.PRODUCE), STARTED)
            default: throw new IllegalArgumentException("No position named " + named)
        }
    }

    /** {@code sending} none where no model is named to review, and nothing yet sent to it by default where one is. */
    private static StepPosition awaiting(List<WaitsOn> on, ReviewSending sending = ReviewSending.UNSENT) {
        new StepPosition.AwaitingReview(1, on.withIndex().collect { waitsOn, index ->
            new StepPosition.WaitingValue(valueId(index + 1), "answer_" + (index + 1), waitsOn)
        }, PERSON, STARTED, sending)
    }

    static RunnableWorkflow workflowOf(List<PlannedStep> steps) {
        new RunnableWorkflow(workflowHalf(DeclarationSide.TAKES), workflowHalf(DeclarationSide.GIVES), [:], [], steps)
    }

    static RunSnapshot run(List<StepSnapshot> steps, boolean stopped = false, StopRecord workflowStopped = null,
                           Object startedWith = [:]) {
        new RunSnapshot(RUN, RUN, GROUP, VERSION, stopped, workflowStopped,
                startedWith == null ? null : json(startedWith) as JsonValue.JsonObject, workflowOf(steps*.planned()),
                steps)
    }
}
