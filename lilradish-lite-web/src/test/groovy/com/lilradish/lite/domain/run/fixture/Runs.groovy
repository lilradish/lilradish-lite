package com.lilradish.lite.domain.run.fixture

import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.codestep.CodeStepDeclaration
import com.lilradish.lite.domain.codestep.ReleasedCodeStep
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.inference.ModelCallOutcome
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.model.ModelToolName
import com.lilradish.lite.domain.registry.EntryId
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.AttemptRecord
import com.lilradish.lite.domain.run.CallRecord
import com.lilradish.lite.domain.run.DecisionRecord
import com.lilradish.lite.domain.run.FailureRecord
import com.lilradish.lite.domain.run.HoldRecord
import com.lilradish.lite.domain.run.ModelCallId
import com.lilradish.lite.domain.run.PinnedEntry
import com.lilradish.lite.domain.run.PlannedStep
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.ProductionValueId
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.ReviewRecord
import com.lilradish.lite.domain.run.ReviewSending
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunSnapshot
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.RunStepHoldReason
import com.lilradish.lite.domain.run.RunStepId
import com.lilradish.lite.domain.run.RunStepSendAttemptId
import com.lilradish.lite.domain.run.RunnableWorkflow
import com.lilradish.lite.domain.run.RunningOn
import com.lilradish.lite.domain.run.StepFailure
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepRuns
import com.lilradish.lite.domain.run.StepSnapshot
import com.lilradish.lite.domain.run.StopRecord
import com.lilradish.lite.domain.run.TryLostReason
import com.lilradish.lite.domain.run.TryRecord
import com.lilradish.lite.domain.run.ValueRecord
import com.lilradish.lite.domain.run.WaitsOn
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.ModelChoice
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.domain.workflow.StepProducer
import java.time.Instant

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
        new PlannedStep(stepId(order), order, new StepId("step_" + order), null,
                asking(order, takes([]), gives([standing("answer")])), producer, tries, reviewer, [])
    }

    static PlannedStep questionTaking(int order, Declaration takes, List<Binding> bindings) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), null,
                asking(order, takes, gives([standing("answer")])), person(), 1, null, bindings)
    }

    /** The code step {@code tidy} as a release declares it, taking and giving back the fields named. */
    static ReleasedCodeStep tidy(boolean mayRunAgain, List<Field> taken = [], List<Field> given = [standing("answer")]) {
        new ReleasedCodeStep(new CodeStepDeclaration(takes(taken), gives(given), mayRunAgain), [:])
    }

    /** A step running {@code tidy} as {@code released} declares it, none where the release holds no such code step. */
    static PlannedStep codeStep(int order, int tries, ReleasedCodeStep released = tidy(false),
                                Producer producer = code(), List<Binding> bindings = []) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), null, new StepRuns.Code("tidy", released),
                producer, tries, null, bindings)
    }

    static SearchWay webSearch(int titleLimit = 120) {
        new SearchWay("web_search", new ModelToolName("search"), titleLimit, 5000, 2000, 300)
    }

    static Map<FieldName, FieldStanding> standingAll(FieldStanding standing) {
        SearchWay.FIELDS.collectEntries { [(it): standing] }
    }

    static StepRuns.Question searchQuestion(int order, SearchWay way = webSearch(), Declaration taken = takes([])) {
        def given = way.outputs().collect { field ->
            new Field(field.name(), field.label(), field.help(), field.shape(), field.howMany(),
                    new Demand.Stands((field.demand() as Demand.Given).mustBe(), FieldStanding.NEVER, null))
        }
        asking(order, taken, gives(given))
    }

    /** A step searching {@code web_search} as {@code released} declares it, none where the release holds no such way. */
    static PlannedStep searchStep(int order, SearchWay released = webSearch(),
                                  StepRuns.Question question = searchQuestion(order), List<Binding> bindings = []) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), null,
                new StepRuns.Search("web_search", released, question, "web_search", standingAll(FieldStanding.ALWAYS)),
                person(), 1, null, bindings)
    }

    static PlannedStep workflowStep(int order) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), null, new StepRuns.Workflow(pinned(order), workflowHalf(DeclarationSide.GIVES)),
                null, null, null, [])
    }

    static PlannedStep routeStep(int order) {
        def discriminator = new Binding(key(600 + order), null, new BindingSource.Written(json("case")))
        def routed = new StepRuns.Route(discriminator, workflowHalf(DeclarationSide.GIVES),
                [new StepRuns.Route.Case(key(700 + order), "case", pinned(order), [])])
        new PlannedStep(stepId(order), order, new StepId("step_" + order), null, routed, null, null, null, [])
    }

    /** A stop in force since {@code since}, made by {@link #STOPPER}. */
    static StopRecord stop(Instant since) {
        new StopRecord(STOPPER, since)
    }

    static StepSnapshot unstarted(PlannedStep planned, StopRecord pinStopped = null) {
        new StepSnapshot(planned, pinStopped, null, null, [], [], null, [:])
    }

    static StepSnapshot started(PlannedStep planned, List<TryRecord> tries, HoldRecord hold = null,
                                List<FailureRecord> failures = [], StopRecord pinStopped = null) {
        new StepSnapshot(planned, pinStopped, new RunStepId(key(500 + planned.order())), hold, failures, tries, null, [:])
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
        new TryRecord(tryId(number), number, producer, null, askedAt(number), null, null, null, null, null, false, null,
                false, false, null, [], [], [], [], [])
    }

    /** A code try without readable words, represented by the system's reason for that outcome. */
    static TryRecord lost(int number, StepProducer producer) {
        new TryRecord(tryId(number), number, producer, null, askedAt(number), endedAt(number), null, null,
                TryLostReason.ERRORED,
                producer == StepProducer.CODE ? new CodeError.Fault(CodeErrorReason.SAID_NOTHING, [], null, null) : null,
                false, null, false, false, null, [], [], [], [], [])
    }

    static TryRecord yielded(int number, StepProducer producer, List<ValueRecord> values,
                             List<ReviewRecord> reviews = [], SubjectId endedBy = null) {
        new TryRecord(tryId(number), number, producer, null, askedAt(number), endedAt(number), endedBy, null, null,
                null, false, null, false, false, null, values, reviews, [], [], [])
    }

    static RunStepSendAttemptId attemptId(int number) {
        new RunStepSendAttemptId(key(1000 + number))
    }

    /** A hold since {@code since}, naming an attempt where its reason needs one, and its model not spent up. */
    static HoldRecord hold(RunStepHoldReason reason, Instant since) {
        boolean onAttempt = reason == RunStepHoldReason.TOO_LONG || reason == RunStepHoldReason.TURNED_AWAY
        new HoldRecord(reason, since, onAttempt ? attemptId(1) : null, false, null)
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
        new DecisionRecord(value.id(), ReviewOutcome.ASSURED, null, null, null)
    }

    static DecisionRecord refused(ValueRecord value) {
        new DecisionRecord(value.id(), ReviewOutcome.REFUSED, "Not what was asked.", null, null)
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
                aTry.endedBy(), aTry.explanation(), aTry.lost(), aTry.fault(), aTry.lostDetailPresent(), aTry.lostDetail(), aTry.lostDetailCut(),
                aTry.returnedPresent(), aTry.returned(), aTry.values(), aTry.reviews(), aTry.inputs(), aTry.attempts() + [attempt],
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
