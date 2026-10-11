package com.lilradish.lite.app.run

import static com.lilradish.lite.domain.declaration.FieldStanding.ABOVE_CONFIDENCE
import static com.lilradish.lite.domain.declaration.FieldStanding.ALWAYS
import static com.lilradish.lite.domain.declaration.FieldStanding.NEVER
import static com.lilradish.lite.domain.run.fixture.Runs.MODEL
import static com.lilradish.lite.domain.run.fixture.Runs.PERSON
import static com.lilradish.lite.domain.run.fixture.Runs.VERSION
import static com.lilradish.lite.domain.run.fixture.Runs.askedAt
import static com.lilradish.lite.domain.run.fixture.Runs.asking
import static com.lilradish.lite.domain.run.fixture.Runs.assured
import static com.lilradish.lite.domain.run.fixture.Runs.attemptId
import static com.lilradish.lite.domain.run.fixture.Runs.code
import static com.lilradish.lite.domain.run.fixture.Runs.codeStep
import static com.lilradish.lite.domain.run.fixture.Runs.endedAt
import static com.lilradish.lite.domain.run.fixture.Runs.gives
import static com.lilradish.lite.domain.run.fixture.Runs.hold
import static com.lilradish.lite.domain.run.fixture.Runs.json
import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.lost
import static com.lilradish.lite.domain.run.fixture.Runs.minutes
import static com.lilradish.lite.domain.run.fixture.Runs.model
import static com.lilradish.lite.domain.run.fixture.Runs.modelReview
import static com.lilradish.lite.domain.run.fixture.Runs.nested
import static com.lilradish.lite.domain.run.fixture.Runs.open
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.personReview
import static com.lilradish.lite.domain.run.fixture.Runs.pinned
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static com.lilradish.lite.domain.run.fixture.Runs.refused
import static com.lilradish.lite.domain.run.fixture.Runs.routeStep
import static com.lilradish.lite.domain.run.fixture.Runs.run
import static com.lilradish.lite.domain.run.fixture.Runs.searchQuestion
import static com.lilradish.lite.domain.run.fixture.Runs.searchStep
import static com.lilradish.lite.domain.run.fixture.Runs.sentToReview
import static com.lilradish.lite.domain.run.fixture.Runs.started
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.stop
import static com.lilradish.lite.domain.run.fixture.Runs.takes
import static com.lilradish.lite.domain.run.fixture.Runs.text
import static com.lilradish.lite.domain.run.fixture.Runs.tidy
import static com.lilradish.lite.domain.run.fixture.Runs.tryId
import static com.lilradish.lite.domain.run.fixture.Runs.unstarted
import static com.lilradish.lite.domain.run.fixture.Runs.value
import static com.lilradish.lite.domain.run.fixture.Runs.valueId
import static com.lilradish.lite.domain.run.fixture.Runs.workflowStep
import static com.lilradish.lite.domain.run.fixture.Runs.yielded

import com.lilradish.lite.app.pool.PersonAnswer
import com.lilradish.lite.app.run.fixture.ChildComposite
import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.file.FileName
import com.lilradish.lite.domain.file.StoredFile
import com.lilradish.lite.domain.filling.FillField
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ModelCallOutcome
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.AttemptRecord
import com.lilradish.lite.domain.run.CallRecord
import com.lilradish.lite.domain.run.DecisionRecord
import com.lilradish.lite.domain.run.FailureRecord
import com.lilradish.lite.domain.run.HoldRecord
import com.lilradish.lite.domain.run.InputRecord
import com.lilradish.lite.domain.run.ModelCallId
import com.lilradish.lite.domain.run.PlannedStep
import com.lilradish.lite.domain.run.ProductionId
import com.lilradish.lite.domain.run.ReviewOutcome
import com.lilradish.lite.domain.run.ReviewRecord
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.RunInTree
import com.lilradish.lite.domain.run.RunSnapshot
import com.lilradish.lite.domain.run.RunStepFailureId
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.RunStepHoldReason
import com.lilradish.lite.domain.run.RunStepId
import com.lilradish.lite.domain.run.RunnableWorkflow
import com.lilradish.lite.domain.run.StepInputs
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepPositions
import com.lilradish.lite.domain.run.StepRuns
import com.lilradish.lite.domain.run.StepSnapshot
import com.lilradish.lite.domain.run.StopRecord
import com.lilradish.lite.domain.run.TryLostReason
import com.lilradish.lite.domain.run.TryRecord
import com.lilradish.lite.domain.run.ValueRecord
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.ModelChoice
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.domain.workflow.StepProducer
import org.mockito.Mockito
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

    static final BindingSource CATEGORY_MADE = new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer"))

    static final BindingSource CATEGORY_STARTED = new BindingSource.WorkflowInput(Pointer.parse("category"))

    static final ValueRecord FIRST_GO = new ValueRecord(valueId(1), "answer", json("First go."), 60, true)

    static final ValueRecord SECOND_GO = new ValueRecord(valueId(2), "answer", json("Second go."), 90, true)

    static final ValueRecord BY_HAND = new ValueRecord(valueId(3), "answer", json("By hand."), null, true)

    static final Binding TAKES_THE_ANSWER = new Binding(key(801), Pointer.parse("answer"),
            new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer")))

    static final RunSnapshot RUNNING = run([
            started(question(1, model(), 3, MODEL), [
                    yielded(1, StepProducer.MODEL, [FIRST_GO], [modelReview(minutes(30), [refused(FIRST_GO)])]),
                    yielded(2, StepProducer.MODEL, [SECOND_GO], [modelReview(minutes(40), [assured(SECOND_GO)])])]),
            started(questionTaking(2, takes([text("answer")]), [TAKES_THE_ANSWER]), [
                    new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(5), null, null, null, null, null,
                            false, null, false, false, null, [], [], [new InputRecord(TAKES_THE_ANSWER.id(), valueId(2), [])], [], [])]),
            started(question(3, person(), 2), [
                    yielded(1, StepProducer.PERSON, [BY_HAND], [personReview(minutes(50), [refused(BY_HAND)])], PERSON)]),
    ])

    static final ModelChoice REVIEWER = new ModelChoice(new ModelName("small"), null)

    static final StepAnswers.WhoAnswer BY_REVIEWER = new StepAnswers.WhoAnswer("model", null, "small", null)

    static final StepAnswers.WhoAnswer BY_A_PERSON = new StepAnswers.WhoAnswer("person", null, null, null)

    static final PlannedStep TAKES_FROM_EACH = questionTaking(4,
            takes([text("from_code"), text("from_question"), text("from_workflow")]),
            [takingAnswerOf(811, "from_code", 1), takingAnswerOf(812, "from_question", 2),
             takingAnswerOf(813, "from_workflow", 3)])

    static final PlannedStep TAKES_FROM_CODE = questionTaking(2, takes([text("from_code")]),
            [takingAnswerOf(821, "from_code", 1)])

    private static StepReading reading(Set<GroupPermission> permitted) {
        reading(RUNNING, permitted)
    }

    def "a length decision on a value outside the fields shown needs no read details"() {
        given:
        def omitted = value(1, "not_declared", true, "Not part of the answer")
        def review = new ReviewRecord(PERSON, minutes(30), true, null, null,
                [new DecisionRecord(omitted.id(), ReviewOutcome.REFUSED, "Too long", 10L, 1L)])
        def source = started(question(1, person(), 2), [yielded(1, StepProducer.PERSON, [omitted], [review], PERSON)])

        when:
        def answer = reading(run([source]), READS_MODELS).step(stepId(1))

        then:
        answer.triesMade()*.number() == [1]
        answer.triesMade()[0].values() == []
        answer.answering().lastRefused().values() == []
    }

    def "a shown length decision still requires read details"() {
        given:
        def shown = value(1, "answer", true, "The answer")
        def review = new ReviewRecord(PERSON, minutes(30), true, null, null,
                [new DecisionRecord(shown.id(), ReviewOutcome.REFUSED, "Too long", 10L, 1L)])
        def source = started(question(1, person(), 2), [yielded(1, StepProducer.PERSON, [shown], [review], PERSON)])
        def snapshot = run([source])

        when:
        reading(snapshot, READS_MODELS).step(stepId(1))

        then:
        thrown(NullPointerException)
    }

    def "a code input read as a file keeps its model source hidden on a saved try and before a try"() {
        given:
        def stored = new StoredFile(key(1901), new FileName("Demo Sample.txt"), 4L)
        def leaves = [text("id"), text("name"),
                      new Field(new FieldName("size"), null, null, new FieldShape.Plain(FieldKind.NUMBER),
                              new HowMany.One(), new Demand.Given(true))]
        def shape = new FieldShape.File([new FileExtension(".txt")], 100L)
        def sourceField = new Field(new FieldName("attachment"), null, null,
                producer == StepProducer.CODE ? shape : new FieldShape.Nested(leaves),
                new HowMany.One(), new Demand.Stands(true, ALWAYS, null))
        def plan = producer == StepProducer.CODE
                ? codeStep(1, 1, tidy(false, [], [sourceField]), code(), [])
                : new PlannedStep(stepId(1), 1, new StepId("step_1"), null,
                        asking(1, takes([]), gives([sourceField])),
                        producer == StepProducer.MODEL ? model() : person(), 1, null, [])
        def sourceValue = new ValueRecord(valueId(1), "attachment", stored.kept(), null, false)
        def source = started(plan, [yielded(1, producer, [sourceValue])])
        def binding = new Binding(key(1902), Pointer.parse("attachment"),
                new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("attachment")))
        def input = new Field(new FieldName("attachment"), null, null, shape,
                new HowMany.One(), new Demand.Given(true))
        def target = codeStep(2, 1, tidy(false, [input]), code(), [binding])
        def saved = new TryRecord(tryId(21), 1, StepProducer.CODE, null, askedAt(2), endedAt(2), null,
                null, null, null, false, null, false, false, null, [value(2, "answer", false, "Demo Sample result")], [],
                [new InputRecord(binding.id(), sourceValue.id(), [])], [], [])
        def snapshot = run([source, historical ? started(target, [saved]) : unstarted(target)])
        def permission = readsModel ? READS_MODELS : READS_NO_MODEL

        when:
        def shown = reading(snapshot, permission).withHeldFiles([(stored.id()): stored]).step(stepId(2)).wentIn()[0]

        then:
        if (producer == StepProducer.MODEL && !readsModel) {
            shown.withheld() == true
            shown.value() == null
        } else {
            shown.withheld() == null
            shown.value().get("id").asString() == stored.id().toString()
            shown.value().get("name").asString() == "Demo Sample.txt"
        }

        where:
        [producer, historical, readsModel] << [[StepProducer.MODEL, StepProducer.CODE, StepProducer.PERSON],
                                               [false, true], [false, true]].combinations()
    }

    def "a composite input withholds a model selected file while keeping a person's neighbouring text"() {
        given:
        def stored = new StoredFile(key(1911), new FileName("Demo Sample.txt"), 4L)
        def file = new Field(new FieldName("attachment"), null, null,
                new FieldShape.File([new FileExtension(".txt")], 100L), new HowMany.One(), new Demand.Given(true))
        def fields = FillField.of([nested("bundle", [file, text("note")])], [:])
        def value = json([bundle: [attachment: [id: stored.id().toString(), name: "Demo Sample.txt",
                                               size: 4], note: "Demo Sample person"]])
        def parts = [new InputRecord.Part([new FieldName("bundle"), new FieldName("attachment")], valueId(1)),
                     new InputRecord.Part([new FieldName("bundle"), new FieldName("note")], valueId(2))]

        when:
        def shown = ShownValues.withheldParts(fields, WrittenValues.of(fields, value), parts,
                readsModel ? READS_MODELS : READS_NO_MODEL,
                [(valueId(1)): producer, (valueId(2)): StepProducer.PERSON])

        then:
        shown.get("bundle").get("note").asString() == "Demo Sample person"
        if (producer == StepProducer.MODEL && !readsModel) {
            shown.get("bundle").get("attachment") == NODES.objectNode().put("withheld", true)
            !shown.get("bundle").get("attachment").has("id")
        } else {
            shown.get("bundle").get("attachment").get("id").asString() == stored.id().toString()
            !shown.get("bundle").get("attachment").has("withheld")
        }

        where:
        [producer, readsModel] << [[StepProducer.MODEL, StepProducer.CODE, StepProducer.PERSON],
                                  [false, true]].combinations()
    }

    def "composite source paths use current declaration order when possible and saved order for an old path"() {
        given:
        def declared = takes([nested("bundle", [text("first"), text("second")])])
        def origin = new RunReadDetails.OriginRecord("constant", null, null, null)
        def second = new RunReadDetails.PartSourceRecord("second", origin)
        def first = new RunReadDetails.PartSourceRecord("first", origin)
        def old = new RunReadDetails.PartSourceRecord("formerly_here", origin)

        expect:
        RunReadDetails.orderedParts(declared, "bundle", [second, first])*.targetPath() == ["first", "second"]
        RunReadDetails.orderedParts(declared, "bundle", [second, old])*.targetPath() == ["second", "formerly_here"]
        RunReadDetails.orderedParts(null, "bundle", [old, second])*.targetPath() == ["formerly_here", "second"]

        when:
        RunReadDetails.orderedParts(declared, "bundle", [old, old])

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "A composite value names an empty or repeated part"
    }

    private static StepReading reading(RunSnapshot run, Set<GroupPermission> permitted,
                                       Map<ProductionId, StepTries.LostRecord> lost = [:],
                                       Map<ProductionId, List<StepTries.TurnawayRecord>> turnedAway = [:]) {
        new StepReading(RunInTree.root(run), 7, true, permitted, Optional.empty(), [:], [:], lost, turnedAway, Deployments.DEEP_ENOUGH)
    }

    def "a historical model component below a field now read as a file hides that leaf and keeps its neighbours"() {
        given:
        def stored = new StoredFile(key(1921), new FileName("Demo Sample.txt"), 4L)
        def file = new Field(new FieldName("attachment"), null, null,
                new FieldShape.File([new FileExtension(".txt")], 100L), new HowMany.One(), new Demand.Given(true))
        def fields = FillField.of([nested("bundle", [file, text("note")])], [:])
        def value = json([bundle: [attachment: [id: stored.id().toString(), name: "Demo Sample.txt",
                                               size: 4], note: "Demo Sample person"]])
        def parts = [new InputRecord.Part([new FieldName("bundle"), new FieldName("attachment"),
                                           new FieldName("id")], valueId(1))]

        when:
        def shown = ShownValues.withheldParts(fields, WrittenValues.of(fields, value), parts, permitted,
                [(valueId(1)): StepProducer.MODEL])

        then:
        shown.get("bundle").get("note").asString() == "Demo Sample person"
        if (permitted.contains(GroupPermission.READ_INFERENCE_CONTENT)) {
            shown.get("bundle").get("attachment").get("id").asString() == stored.id().toString()
            !shown.get("bundle").get("attachment").has("withheld")
        } else {
            shown.get("bundle").get("attachment") == NODES.objectNode().put("withheld", true)
            !shown.get("bundle").get("attachment").has("id")
        }

        where:
        permitted << [READS_NO_MODEL, READS_MODELS]
    }

    private static TryRecord sent(int number, TryLostReason lost, List<Integer> attempts) {
        new TryRecord(tryId(number), number, StepProducer.MODEL, null, askedAt(number),
                lost == null ? null : endedAt(number), null, null, lost, null, false, null, false, false, null, [], [], [],
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

    private static Field givesStanding(FieldStanding standing, String name = "answer") {
        new Field(new FieldName(name), null, null, new FieldShape.Text(200), new HowMany.One(),
                new Demand.Stands(true, standing, standing == ABOVE_CONFIDENCE ? 80 : null))
    }

    private static PlannedStep giving(List<FieldStanding> standings, Producer producer, ModelChoice reviewer = null) {
        def fields = standings.withIndex().collect { standing, index -> givesStanding(standing, "field_" + index) }
        new PlannedStep(stepId(1), 1, new StepId("step_1"), null, asking(1, takes([]), gives(fields)), producer, 2, reviewer,
                [])
    }

    private static Producer researching() {
        new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), false)
    }

    private static FailureRecord notHeldOn(int number, ModelCallPurpose purpose) {
        new FailureRecord(new RunStepFailureId(key(1101)), RunStepFailureReason.MODEL_NOT_DEPLOYED, tryId(number),
                purpose, minutes(15), false, null, null, null)
    }

    private static FailureRecord uncuttableOn(int number) {
        new FailureRecord(new RunStepFailureId(key(1102)), RunStepFailureReason.UNCUTTABLE_LENGTH, tryId(number),
                ModelCallPurpose.PRODUCE, minutes(15), false, null, null, null)
    }

    private static RunSnapshot unclaimedBy(BindingSource source, StepProducer producer) {
        def made = started(question(1, producer == StepProducer.MODEL ? model() : person(), 1),
                [yielded(1, producer, [new ValueRecord(valueId(1), "answer", json("wait"),
                        producer == StepProducer.MODEL ? 60 : null, false)])])
        run([made, routeFailedOn(source, 2)], false, null, [category: "wait"])
    }

    private static StepSnapshot routeFailedOn(BindingSource source, int order = 1) {
        def template = routeStep(order)
        def planned = new PlannedStep(template.id(), template.order(), template.name(), template.label(),
                new StepRuns.Route(new Binding(key(851), null, source), template.runs().gives(),
                        [new StepRuns.Route.Case(key(852), "go", pinned(2), [])]), null, null, null, [])
        def failure = new FailureRecord(new RunStepFailureId(key(1104)), RunStepFailureReason.UNCLAIMED_VALUE, null,
                null, minutes(15), false, "wait", null, null)
        started(planned, [], null, [failure])
    }

    private static RunSnapshot beneath(RunSnapshot template, int id) {
        new RunSnapshot(new RunId(key(id)), template.root(), template.group(), template.version(), false, null, null,
                template.workflow(), template.steps())
    }

    private static Binding takingAnswerOf(int numbered, String target, int order) {
        new Binding(key(numbered), Pointer.parse(target),
                new BindingSource.StepOutput(stepId(order).value(), Pointer.parse("answer")))
    }

    def "a child awaiting its first person's answer leaves the parent running without claiming a system try"() {
        given:
        def template = run([unstarted(question(1, person(), 1))])
        def child = new RunSnapshot(new RunId(key(1202)), template.root(), template.group(), template.version(),
                false, null, null, template.workflow(), template.steps())
        def parent = new StepSnapshot(workflowStep(1), null, new RunStepId(key(1203)),
                null, [], [], new StepSnapshot.Beneath(child, null), [:])

        when:
        def row = reading(run([parent]), READS_MODELS).steps().steps()[0]

        then:
        row.state() == "running"
        row.where().kind() == "running"
        row.where().on() == null
        row.where().waitsOn() == null
        row.next() == null
        row.stateActs().empty
    }

    def "a parent row reads the child hold and its own workflow stop context"() {
        given:
        def childStep = started(question(1, person(), 1), [],
                hold(RunStepHoldReason.ENTRY_STOPPED, minutes(50)))
        def childStopper = new SubjectId(key(1204))
        def parentStop = stop(minutes(30))
        def template = run([childStep], false, new StopRecord(childStopper, minutes(40)))
        def child = new RunSnapshot(new RunId(key(1200)), template.root(), template.group(), template.version(),
                false, template.workflowStopped(), null, template.workflow(), template.steps())
        def parent = new StepSnapshot(workflowStep(1), parentStop, new RunStepId(key(1201)),
                null, [], [], new StepSnapshot.Beneath(child, null), [:])
        def top = run([parent])
        def childPerson = new PersonAnswer("child", "Child")
        def parentPerson = new PersonAnswer("parent", "Parent")

        when:
        def read = new StepReading(RunInTree.root(top), 7, true, READS_MODELS, Optional.empty(),
                [(childStopper): childPerson, (parentStop.by()): parentPerson], [:], [:], [:], Deployments.DEEP_ENOUGH).steps()
        def row = read.steps()[0]

        then:
        row.state() == "held_back"
        row.where().kind() == "held_back"
        row.where().reason() == RunStepHoldReason.ENTRY_STOPPED.published()
        row.where().stopped().what() == "workflow"
        row.where().stopped().by() == childPerson
        row.where().stopped().by() != parentPerson
        row.where().stopped().at() == minutes(40).toString()
        StepReading.named(top) == [childStopper, parentStop.by()] as Set
        row.where().waitsOn() == "starter"
        row.next() == null
        row.stateActs().empty
        read.run().at() == new RunsController.AtAnswer(parent.planned().id().value(),
                parent.planned().name().value(), parent.planned().label()?.value())
    }

    def "a completed child does not name its stopped person beside the current child"() {
        given:
        def oldStopper = new SubjectId(key(1210))
        def currentStopper = new SubjectId(key(1211))
        def oldTemplate = run([], false, new StopRecord(oldStopper, minutes(50)))
        def oldChild = new RunSnapshot(new RunId(key(1212)), oldTemplate.root(), oldTemplate.group(),
                oldTemplate.version(), false, oldTemplate.workflowStopped(), null, oldTemplate.workflow(),
                oldTemplate.steps())
        def currentStep = started(question(1, person(), 1), [],
                hold(RunStepHoldReason.ENTRY_STOPPED, minutes(40)))
        def currentTemplate = run([currentStep], false, new StopRecord(currentStopper, minutes(30)))
        def currentChild = new RunSnapshot(new RunId(key(1213)), currentTemplate.root(), currentTemplate.group(),
                currentTemplate.version(), false, currentTemplate.workflowStopped(), null,
                currentTemplate.workflow(), currentTemplate.steps())
        def oldParent = new StepSnapshot(workflowStep(1), null, new RunStepId(key(1214)),
                null, [], [], new StepSnapshot.Beneath(oldChild, null), [:])
        def currentParent = new StepSnapshot(workflowStep(2), null, new RunStepId(key(1215)),
                null, [], [], new StepSnapshot.Beneath(currentChild, null), [:])
        def top = run([oldParent, currentParent])

        when:
        def positions = StepPositions.of(top)
        def named = StepReading.named(top)

        then:
        positions[0] instanceof StepPosition.Done
        positions[1] instanceof StepPosition.Beneath
        named == [currentStopper] as Set
        !named.contains(oldStopper)
    }

    def "a child waiting for review gives the parent its waiting values without parent actions"() {
        given:
        def childStep = started(question(1, person(), 2),
                [yielded(1, StepProducer.PERSON, [BY_HAND], [], PERSON)])
        def template = run([childStep])
        def child = new RunSnapshot(new RunId(key(1205)), template.root(), template.group(), template.version(),
                false, null, null, template.workflow(), template.steps())
        def parent = new StepSnapshot(workflowStep(1), null, new RunStepId(key(1206)),
                null, [], [], new StepSnapshot.Beneath(child, null), [:])

        def top = run([parent])

        when:
        def read = reading(top, READS_MODELS).steps()
        def row = read.steps()[0]

        then:
        StepPositions.of(child)[0] instanceof StepPosition.AwaitingReview
        StepPositions.of(top)[0] instanceof StepPosition.Beneath
        read.run().state() == "running"
        read.run().at().stepId() == parent.planned().id().value()
        row.state() == "waiting"
        row.where().kind() == "waiting_on_review"
        row.where().number() == 1
        row.where().values() == [new StepAnswers.WaitingValueAnswer("answer", "review_at_gate")]
        row.where().waitsOn() == "review_at_gate"
        row.next() == null
        row.stateActs().empty
    }

    def "a parent row preserves a child's turnaway order and hides model words from a reader without permission"() {
        given:
        def childStep = started(question(1, model(), 3), [sent(1, null, [2, 1])],
                new HoldRecord(RunStepHoldReason.TURNED_AWAY, minutes(20), attemptId(1), false, null))
        def template = run([childStep])
        def child = new RunSnapshot(new RunId(key(1207)), template.root(), template.group(), template.version(),
                false, null, null, template.workflow(), template.steps())
        def parent = new StepSnapshot(workflowStep(1), null, new RunStepId(key(1208)),
                null, [], [], new StepSnapshot.Beneath(child, null), [:])
        def top = run([parent])
        def histories = [(tryId(1)): [
                new StepTries.TurnawayRecord(attemptId(2), minutes(11), "Busy.", false, true),
                new StepTries.TurnawayRecord(attemptId(1), minutes(12), "Spent.", true, false)]]

        when:
        def visible = reading(top, READS_MODELS, [:], histories).steps().steps()[0]
        def hidden = reading(top, READS_NO_MODEL, [:], histories).steps().steps()[0]

        then:
        StepReading.visibleProductions(top) == [tryId(1)]
        !StepReading.namesCalls(top)
        visible.where().kind() == "held_back"
        visible.where().turnedAway() == [
                new StepAnswers.TurnawayAnswer(minutes(11).toString(), "Busy.", false, null, true),
                new StepAnswers.TurnawayAnswer(minutes(12).toString(), "Spent.", true, null, false)]
        hidden.where().turnedAway() == [
                new StepAnswers.TurnawayAnswer(minutes(11).toString(), null, null, true, true),
                new StepAnswers.TurnawayAnswer(minutes(12).toString(), null, null, true, false)]
        visible.next() == null
        hidden.stateActs().empty
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
                new StepAnswers.DecisionAnswer("refused", null, true, null, null, null), null)]

        and: "the second's assured with no words to hold back, so none is said to be held back"
        tries[1].values() == [new StepAnswers.TriedValueAnswer("answer", null, true, "stands", null,
                new StepAnswers.DecisionAnswer("assured", null, null, null, null, null), null)]
    }

    def "a reader who may read what a model said reads every value, how sure it was, and the words it was refused with"() {
        when:
        def tries = reading(READS_MODELS).step(stepId(1)).triesMade()

        then:
        tries*.values() == [
                [new StepAnswers.TriedValueAnswer("answer", NODES.stringNode("First go."), null, "refused", 60,
                        new StepAnswers.DecisionAnswer("refused", "Not what was asked.", null, null, null, null), null)],
                [new StepAnswers.TriedValueAnswer("answer", NODES.stringNode("Second go."), null, "stands", 90,
                        new StepAnswers.DecisionAnswer("assured", null, null, null, null, null), null)]]
    }

    def "what a model gave back is withheld where it stands, and where it went into the next step"() {
        when:
        def cameOut = reading(permitted).step(stepId(1)).cameOut()
        def wentIn = reading(permitted).step(stepId(2)).wentIn()

        then:
        cameOut == [new StepAnswers.CameOutAnswer("answer", new StepAnswers.StandingAnswer(2, value, withheld, null),
                "stands", null, null, null)]
        wentIn == [new StepAnswers.WentInAnswer("answer",
                new StepAnswers.FromAnswer("step", "answer", stepId(1).value(), "step_1", null, key(201), null, key(1), 7, null), value,
                withheld, null, null)]

        where:
        permitted      || value                          | withheld
        READS_NO_MODEL || null                           | true
        READS_MODELS   || NODES.stringNode("Second go.") | null
    }

    def "a child composite input keeps person parts beside withheld model text with or without a code declaration"() {
        given:
        def scenario = ChildComposite.of(codeNow)
        def context = scenario.context()
        def nameSource = scenario.nameSource()
        def idSource = scenario.idSource()
        def parentRun = new RunReadDetails.NumberedRunRecord(context.above().run().run(), 7)
        def modelOrigin = new RunReadDetails.OriginRecord("step", null, parentRun,
                new RunReadDetails.MakerRecord(parentRun, stepId(1).value(), "step_1", "answer", 1))
        def personOrigin = new RunReadDetails.OriginRecord("step", null, parentRun,
                new RunReadDetails.MakerRecord(parentRun, stepId(2).value(), "step_2", "answer", 1))
        def parts = ["id", "name", "type", "size", "secret"].collect { name ->
            new RunReadDetails.PartSourceRecord(name, name == "secret" ? modelOrigin : personOrigin)
        }
        def details = Mockito.mock(RunReadDetails)
        Mockito.when(details.costBeneath(Mockito.anyList())).thenReturn([:])
        Mockito.when(details.parts(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyList())).thenReturn(parts)
        Mockito.when(details.planned(modelOrigin.maker())).thenReturn(nameSource.planned())
        Mockito.when(details.planned(personOrigin.maker())).thenReturn(idSource.planned())
        Mockito.when(details.origin(Mockito.any(), Mockito.any(StepInputs.BindingRecord) as StepInputs.BindingRecord))
                .thenAnswer { call ->
                    call.getArgument(1).binding().target().published().endsWith("secret") ? modelOrigin : personOrigin
                }

        when:
        def hiddenReading = new StepReading(context, 8, false, READS_NO_MODEL, Optional.empty(),
                [:], [:], [:], [:], Deployments.DEEP_ENOUGH).withDetails(details)
        def shownReading = new StepReading(context, 8, false, READS_MODELS, Optional.empty(),
                [:], [:], [:], [:], Deployments.DEEP_ENOUGH).withDetails(details)
        def hidden = hiddenReading.step(stepId(1)).wentIn()[0]
        def shown = shownReading.step(stepId(1)).wentIn()[0]

        then:
        hidden.value() == (codeNow
                ? NODES.stringNode('{"id":"123","name":"123","type":"123","size":"123","secret":{"withheld":true}}')
                : NODES.objectNode().put("id", "123").put("name", "123").put("type", "123")
                        .put("size", "123").set("secret", NODES.objectNode().put("withheld", true)))
        hidden.withheld() == null
        hidden.earlierShape() == (codeNow ? true : null)
        shown.value() == (codeNow
                ? NODES.stringNode('{"id":"123","name":"123","type":"123","size":"123","secret":"Ari"}')
                : NODES.objectNode().put("id", "123").put("name", "123").put("type", "123")
                        .put("size", "123").put("secret", "Ari"))
        shown.withheld() == null
        shown.earlierShape() == (codeNow ? true : null)
        hidden.from() == null
        shown.from() == null
        hidden.sources()*.targetPath() == ["id", "name", "type", "size", "secret"]
        shown.sources()*.targetPath() == hidden.sources()*.targetPath()
        hidden.sources()*.from()*.kind() == ["step"] * 5
        hidden.sources()*.from()*.runId() == [parentRun.run().value()] * 5
        hidden.sources()*.from()*.runNumber() == [7] * 5
        hidden.sources()*.from()*.stepId() == [stepId(2).value()] * 4 + [stepId(1).value()]
        !hidden.toString().contains("Ari")

        where:
        codeNow << [false, true]
    }

    def "a mixed value keeps the code file beside withheld model text in both permission directions"() {
        given:
        def note = new FillField(new FieldName("note"), null, null, FieldKind.TEXT, 100, null, false, null, [])
        def file = new FillField(new FieldName("attachment"), null, null, FieldKind.FILE, null, null, false,
                null, [], [new FileExtension(".pdf")], 100L)
        def bundle = new FillField(new FieldName("bundle"), null, null, FieldKind.FIELDS, null, null, false,
                null, [note, file])
        def kept = json([bundle: [note: "Demo Sample model text", attachment: [
                id: "00000000-0000-4000-8000-000000000321", name: "Demo Sample.pdf",
                size: 12]]]) as com.lilradish.lite.domain.wire.JsonValue.JsonObject
        def parts = [new InputRecord.Part(Pointer.parse("bundle.note").names(), valueId(41)),
                     new InputRecord.Part(Pointer.parse("bundle.attachment").names(), valueId(42))]
        def producers = [(valueId(41)): StepProducer.MODEL, (valueId(42)): StepProducer.CODE]

        when:
        def hidden = ShownValues.withheldParts([bundle], WrittenValues.of([bundle], kept), parts,
                READS_NO_MODEL, producers)
        def shown = ShownValues.withheldParts([bundle], WrittenValues.of([bundle], kept), parts,
                READS_MODELS, producers)

        then:
        hidden.get("bundle").get("note") == NODES.objectNode().put("withheld", true)
        shown.get("bundle").get("note").asString() == "Demo Sample model text"
        hidden.get("bundle").get("attachment") == shown.get("bundle").get("attachment")
        hidden.get("bundle").get("attachment").get("size").asLong() == 12L
        !hidden.toString().contains("Demo Sample model text")
    }

    def "an empty optional model object is withheld while file and person siblings stay readable"() {
        given:
        def details = new FillField(new FieldName("details"), null, null, FieldKind.FIELDS, null, null, false,
                null, [new FillField(new FieldName("inside"), null, null, FieldKind.TEXT, 100, null, false, null, [])])
        def file = new FillField(new FieldName("attachment"), null, null, FieldKind.FILE, null, null, false,
                null, [], [new FileExtension(".pdf")], 100L)
        def personNote = new FillField(new FieldName("note"), null, null, FieldKind.TEXT, 100, null, false, null, [])
        def bundle = new FillField(new FieldName("bundle"), null, null, FieldKind.FIELDS, null, null, false,
                null, [details, file, personNote])
        def kept = json([bundle: [details: null, attachment: [
                id: "00000000-0000-4000-8000-000000000322", name: "Demo Sample.pdf",
                size: 12], note: "Demo Sample person"]])
                as com.lilradish.lite.domain.wire.JsonValue.JsonObject
        def parts = [new InputRecord.Part(Pointer.parse("bundle.details").names(), valueId(43)),
                     new InputRecord.Part(Pointer.parse("bundle.attachment").names(), valueId(44)),
                     new InputRecord.Part(Pointer.parse("bundle.note").names(), valueId(45))]
        def producers = [(valueId(43)): StepProducer.MODEL, (valueId(44)): StepProducer.CODE,
                         (valueId(45)): StepProducer.PERSON]

        when:
        def hidden = ShownValues.withheldParts([bundle], WrittenValues.of([bundle], kept), parts,
                READS_NO_MODEL, producers)
        def shown = ShownValues.withheldParts([bundle], WrittenValues.of([bundle], kept), parts,
                READS_MODELS, producers)

        then:
        hidden.get("bundle").get("details") == NODES.objectNode().put("withheld", true)
        shown.get("bundle").get("details").isNull()
        hidden.get("bundle").get("attachment") == shown.get("bundle").get("attachment")
        hidden.get("bundle").get("attachment").get("size").asLong() == 12L
        hidden.get("bundle").get("note").asString() == "Demo Sample person"
        shown.get("bundle").get("note") == hidden.get("bundle").get("note")
    }

    def "a child binding carries an empty model object beside a person value through real input resolution"() {
        given:
        def modelField = new Field(new FieldName("details"), null, null,
                new FieldShape.Nested([text("inside")]), new HowMany.One(), new Demand.Stands(false, ALWAYS, null))
        def modelPlan = new PlannedStep(stepId(1), 1, new StepId("step_1"), null,
                asking(1, takes([]), gives([modelField])), model(), 1, null, [])
        def modelSource = started(modelPlan,
                [yielded(1, StepProducer.MODEL, [value(43, "details", false, null)])])
        def personSource = started(question(2, person(), 1),
                [yielded(1, StepProducer.PERSON, [value(44, "answer", false, "Demo Sample person")])])
        def childTakes = new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES),
                [nested("bundle", [nested("details", [text("inside")]), text("note")])])
        def childWorkflow = new RunnableWorkflow(childTakes,
                new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), []), [:], [], [])
        def template = run([])
        def child = new RunSnapshot(new RunId(key(1261)), template.root(), template.group(), template.version(),
                false, null, null, childWorkflow, [])
        def bindings = [
                new Binding(key(1262), Pointer.parse("bundle.details"),
                        new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("details"))),
                new Binding(key(1263), Pointer.parse("bundle.note"),
                        new BindingSource.StepOutput(stepId(2).value(), Pointer.parse("answer")))]
        def parentPlan = new PlannedStep(stepId(3), 3, new StepId("step_3"), null,
                new StepRuns.Workflow(pinned(3), child.workflow().gives()), null, null, null, bindings)
        def parentStep = new StepSnapshot(parentPlan, null, new RunStepId(key(1264)), null, [], [],
                new StepSnapshot.Beneath(child, null), [:])
        def context = RunInTree.root(run([modelSource, personSource, parentStep])).beneath(parentStep)
        def fields = FillField.of(childTakes.fields(), [:])

        when:
        def bound = bindings.collect { StepInputs.bound(context.above(), it).orElseThrow() }
        def parts = bound.collect { new InputRecord.Part(it.binding().target().names(), it.source()) }
        def kept = StepInputs.filled(childTakes, bound)
        def hidden = ShownValues.withheldParts(fields, WrittenValues.of(fields, kept), parts,
                READS_NO_MODEL, ShownValues.producersIn(context))
        def shown = ShownValues.withheldParts(fields, WrittenValues.of(fields, kept), parts,
                READS_MODELS, ShownValues.producersIn(context))

        then:
        bound*.source() == [valueId(43), valueId(44)]
        kept.members()[0].value().members()[0].value() == json(null)
        hidden.get("bundle").get("details") == NODES.objectNode().put("withheld", true)
        shown.get("bundle").get("details").isNull()
        hidden.get("bundle").get("note").asString() == "Demo Sample person"
        shown.get("bundle").get("note") == hidden.get("bundle").get("note")
    }

    def "a step calling a model says what its tries spent between them and each apart, and one calling none says only that"() {
        given:
        def spent = [(tryId(1)): new RunBudget.Spend(100, 40, false, 0, 1),
                     (tryId(2)): new RunBudget.Spend(30, 0, true, 1, 1)]
        def read = new StepReading(RunInTree.root(RUNNING), 7, true, READS_MODELS, Optional.empty(), [:], spent, [:], [:], Deployments.DEEP_ENOUGH)

        when:
        def rows = read.steps().steps()
        def tries = read.step(stepId(1)).triesMade()

        then:
        rows[0].cost() == new StepAnswers.CostAnswer(true, "130", "40", "170", true, "partly")
        tries*.cost() == [new StepAnswers.CostAnswer(true, "100", "40", "140", false, null),
                          new StepAnswers.CostAnswer(true, "30", "0", "30", true, "wholly")]

        and: "a step no model produces or reviews counts none of it, whatever its tries are keyed by"
        rows[2].cost() == new StepAnswers.CostAnswer(false, null, null, null, null, null)
        read.step(stepId(3)).triesMade()*.cost() == [new StepAnswers.CostAnswer(false, null, null, null, null, null)]
    }

    def "what a person gave, and the words a person refused it with, are held back from nobody"() {
        when:
        def tries = reading(READS_NO_MODEL).step(stepId(3)).triesMade()

        then:
        tries*.values() == [[new StepAnswers.TriedValueAnswer("answer", NODES.stringNode("By hand."), null, "refused",
                null, new StepAnswers.DecisionAnswer("refused", "Not what was asked.", null, null, null, null), null)]]
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
                new HoldRecord(reason, minutes(20), attemptId(1), spentUp, null))])
        def turned = [(tryId(1)): [new StepTries.TurnawayRecord(attemptId(2), minutes(11), "Busy.", false, true),
                                   new StepTries.TurnawayRecord(attemptId(1), minutes(12), "Spent.", true, false)]]

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

        and: "nothing a hold says, nor what only a route's or a run too deep failing says"
        whereabouts.spentUp() == null
        whereabouts.turnedAway() == null
        whereabouts.stopped() == null
        whereabouts.failedUnclaimed() == null
        whereabouts.failedTooDeep() == null

        where:
        planned                                  | tries                                         | failures                                      || reason               | modelNamed | mode       | reviewing
        question(1, model(), 3)                  | [open(1, StepProducer.MODEL)]                 | [notHeldOn(1, ModelCallPurpose.PRODUCE)]      || "model_not_deployed" | "general"  | null       | null
        question(1, researching(), 3)            | [open(1, StepProducer.MODEL)]                 | [notHeldOn(1, ModelCallPurpose.PRODUCE)]      || "model_not_deployed" | "general"  | "research" | null
        question(1, person(), 2, REVIEWER)       | [yielded(1, StepProducer.PERSON, [BY_HAND])]  | [notHeldOn(1, ModelCallPurpose.REVIEW)]       || "model_not_deployed" | "small"    | null       | true
        question(1, researching(), 3, REVIEWER)  | [yielded(1, StepProducer.MODEL, [SECOND_GO])] | [notHeldOn(1, ModelCallPurpose.REVIEW)]       || "model_not_deployed" | "small"    | null       | true
        question(1, model(), 3)                  | [open(1, StepProducer.MODEL)]                 | [uncuttableOn(1)]                             || "uncuttable_length"  | null       | null       | null
        question(1, model(), 1)                  | [sent(1, TryLostReason.ERRORED, [1])]         | []                                            || "tries_spent"        | null       | null       | null
    }

    /** How deep runs may go is read as the deployment says it now, never as it said when the step failed. */
    def "a step failed on a value no case claimed names that value, and one failed too deep how deep runs may go, offering nothing"() {
        given:
        def failure = new FailureRecord(new RunStepFailureId(key(1103)), reason, null, null, minutes(15), false, term, null, null)
        def failed = run([started(planned, [], null, [failure])])

        when:
        def row = new StepReading(RunInTree.root(failed), 7, true, EnumSet.allOf(GroupPermission),
                Optional.empty(), [:], [:], [:], [:], new RunDeployment(5)).steps().steps()[0]

        then:
        row.where().kind() == "failed"
        row.where().reason() == reason.published()
        row.where().failedUnclaimed() == unclaimed
        row.where().failedTooDeep() == tooDeep

        and: "nothing to act on, nothing withheld, and nothing a model's failure names"
        row.stateActs() == []
        row.stateRefusals() == []
        row.where().model() == null
        row.where().spentUp() == null

        where:
        planned         | reason                                | term   || unclaimed                                     | tooDeep
        routeStep(1)    | RunStepFailureReason.UNCLAIMED_VALUE  | "wait" || new StepAnswers.UnclaimedAnswer.Named("wait") | null
        routeStep(1)    | RunStepFailureReason.UNCLAIMED_VALUE  | null   || new StepAnswers.UnclaimedAnswer.Named(null)   | null
        routeStep(1)    | RunStepFailureReason.TOO_DEEP         | null   || null                                          | new StepAnswers.TooDeepAnswer("5")
        workflowStep(1) | RunStepFailureReason.TOO_DEEP         | null   || null                                          | new StepAnswers.TooDeepAnswer("5")
    }

    /** As the value it was chosen by is withheld wherever else it is shown, judged through the route's own binding. */
    def "a value no case claimed is withheld from a reader who may not read what a model said, and named to every other"() {
        given:
        def failed = unclaimedBy(source, producer)

        when:
        def where = new StepReading(RunInTree.root(failed), 7, true, permitted, Optional.empty(), [:], [:], [:],
                [:], Deployments.DEEP_ENOUGH).steps().steps()[1].where()

        then:
        where.reason() == "unclaimed_value"
        where.failedUnclaimed() == shown

        and:
        where.failedTooDeep() == null

        where:
        source           | producer           | permitted      || shown
        CATEGORY_MADE    | StepProducer.MODEL  | READS_NO_MODEL || new StepAnswers.UnclaimedAnswer.Withheld(true)
        CATEGORY_MADE    | StepProducer.MODEL  | READS_MODELS   || new StepAnswers.UnclaimedAnswer.Named("wait")
        CATEGORY_MADE    | StepProducer.PERSON | READS_NO_MODEL || new StepAnswers.UnclaimedAnswer.Named("wait")
        CATEGORY_STARTED | StepProducer.PERSON | READS_NO_MODEL || new StepAnswers.UnclaimedAnswer.Named("wait")
    }

    /** A run beneath that failed so is read from the step above it, withheld or named as it would be in its own run. */
    def "a value no case claimed in a run beneath is withheld or named the same read from the step that started it"() {
        given:
        def child = beneath(unclaimedBy(CATEGORY_MADE, producer), 1300)
        def parent = run([new StepSnapshot(workflowStep(1), null, new RunStepId(key(1301)), null, [], [],
                new StepSnapshot.Beneath(child, null), [:])])

        when:
        def where = new StepReading(RunInTree.root(parent), 7, true, permitted, Optional.empty(), [:], [:], [:],
                [:], Deployments.DEEP_ENOUGH).steps().steps()[0].where()

        then:
        where.reason() == "unclaimed_value"
        where.failedUnclaimed() == shown

        and:
        where.failedTooDeep() == null

        where:
        producer            | permitted      || shown
        StepProducer.MODEL  | READS_NO_MODEL || new StepAnswers.UnclaimedAnswer.Withheld(true)
        StepProducer.MODEL  | READS_MODELS   || new StepAnswers.UnclaimedAnswer.Named("wait")
        StepProducer.PERSON | READS_NO_MODEL || new StepAnswers.UnclaimedAnswer.Named("wait")
    }

    /** Traced through the run above, as a value a run beneath was started with was made there. */
    def "a value no case claimed that a run beneath took from the run above is withheld or named alike on either page"() {
        given:
        def made = started(question(1, producer == StepProducer.MODEL ? model() : person(), 1),
                [yielded(1, producer, [new ValueRecord(valueId(1), "answer", json("wait"),
                        producer == StepProducer.MODEL ? 60 : null, false)])])
        def child = beneath(run([routeFailedOn(CATEGORY_STARTED)]), 1310)
        def calling = new PlannedStep(stepId(2), 2, new StepId("step_2"), null, new StepRuns.Workflow(pinned(2), child.workflow().gives()), null, null,
                null, [new Binding(key(890), Pointer.parse("category"), CATEGORY_MADE)])
        def callingStep = new StepSnapshot(calling, null, new RunStepId(key(1311)), null, [], [],
                new StepSnapshot.Beneath(child, null), [:])
        def parent = run([made, callingStep])

        when:
        def onParent = new StepReading(RunInTree.root(parent), 7, true, permitted, Optional.empty(), [:], [:],
                [:], [:], Deployments.DEEP_ENOUGH).steps().steps()[1].where().failedUnclaimed()
        def onChild = new StepReading(RunInTree.root(parent).beneath(callingStep), 8, false, permitted,
                Optional.empty(), [:], [:], [:], [:], Deployments.DEEP_ENOUGH).steps().steps()[0].where().failedUnclaimed()

        then:
        onParent == shown
        onChild == shown

        where:
        producer            | permitted      || shown
        StepProducer.MODEL  | READS_NO_MODEL || new StepAnswers.UnclaimedAnswer.Withheld(true)
        StepProducer.MODEL  | READS_MODELS   || new StepAnswers.UnclaimedAnswer.Named("wait")
        StepProducer.PERSON | READS_NO_MODEL || new StepAnswers.UnclaimedAnswer.Named("wait")
    }

    /** The stop is what keeps Try sending from it, so it is read beside the reason the step is held back or failed. */
    def "a model's step held back or failed while what it runs is stopped names that stop, its own reason unchanged, and none where nothing is"() {
        given:
        def held = run([started(question(1, model(), 3), tries, hold, failures, stopped)])
        def turned = [(tryId(1)): [new StepTries.TurnawayRecord(attemptId(1), minutes(12), "Spent.", true, false)]]

        when:
        def whereabouts = reading(held, READS_MODELS, [:], turned).steps().steps()[0].where()

        then:
        whereabouts.kind() == kind
        whereabouts.reason() == reason
        whereabouts.stopped() == shownStop
        whereabouts.turnedAway() == turnedAway

        where:
        tries                         | hold                                                                         | failures                                 | stopped           || kind        | reason               | shownStop                                                          | turnedAway
        [sent(1, null, [1])]          | new HoldRecord(RunStepHoldReason.TURNED_AWAY, minutes(20), attemptId(1), false, null) | []                                    | stop(minutes(25)) || "held_back" | "turned_away"        | new StepAnswers.StoppedAnswer("entry", null, minutes(25).toString()) | [new StepAnswers.TurnawayAnswer(minutes(12).toString(), "Spent.", true, null, false)]
        [sent(1, null, [1])]          | new HoldRecord(RunStepHoldReason.TURNED_AWAY, minutes(20), attemptId(1), false, null) | []                                    | null              || "held_back" | "turned_away"        | null                                                               | [new StepAnswers.TurnawayAnswer(minutes(12).toString(), "Spent.", true, null, false)]
        [sent(1, null, [1])]          | new HoldRecord(RunStepHoldReason.TOO_LONG, minutes(20), attemptId(1), false, null)    | []                                    | stop(minutes(25)) || "held_back" | "too_long"           | new StepAnswers.StoppedAnswer("entry", null, minutes(25).toString()) | null
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
        def why = [(tryId(1)): new StepTries.LostRecord(DidNotFitReason.CUT_OFF, null, false, false),
                   (tryId(2)): new StepTries.LostRecord(null, "Upstream failed.", true, false)]

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

    /** Code's own evidence can be opened by either reader, so its presence is not withheld with model words. */
    def "code error evidence is named for either reader without loading its original words in the summary"() {
        given:
        def wentWrongInCode = new TryRecord(tryId(1), 1, StepProducer.CODE, null, askedAt(1), endedAt(1), null, null,
                TryLostReason.ERRORED, null, true, "The mail server refused it.", false, false, null, [], [], [], [], [])

        when:
        def tries = reading(run([started(codeStep(1, 1), [wentWrongInCode])]), permitted).step(stepId(1)).triesMade()

        then:
        tries*.wentWrong() == [null]
        tries*.wentWrongPresent() == [true]
        tries*.erroredFor() == [null]
        tries*.didNotFit() == [null]
        tries*.turnedAway() == [null]

        where:
        permitted << [READS_NO_MODEL, READS_MODELS]
    }

    /**
     * This system's reason stays in the summary for either reader; the original answer's presence is named
     * separately, and its absence is said only where the reason concerns something code gave back.
     */
    def "a try of code gone wrong for this system's reason reads it, what it is about and reads it, whether the member it names was cut, and whether what came back was lost"() {
        given:
        def erred = new TryRecord(tryId(1), 1, StepProducer.CODE, null, askedAt(1), endedAt(1), null, null,
                TryLostReason.ERRORED, fault, false, null, false, (returned) != null, returned, [], [], [], [], [])

        when:
        def tries = reading(run([started(codeStep(1, 1), [erred])]), permitted).step(stepId(1)).triesMade()

        then:
        tries*.erroredFor() == [answer]
        tries*.wentWrong() == [null]
        tries*.returnedPresent() == [returned != null]

        where:
        [permitted, fault, returned, answer] << [[READS_NO_MODEL, READS_MODELS], [
                [codeFault(CodeErrorReason.UNKEEPABLE, "receipt", null, null), null,
                 new StepAnswers.ErroredForAnswer("unkeepable", "receipt", null, null, null, true)],
                [codeFault(CodeErrorReason.TOO_LONG, "receipt", null, null), '{"receipt":"R-123456789"}',
                 new StepAnswers.ErroredForAnswer("too_long", "receipt", null, null, null, null)],
                [codeFault(CodeErrorReason.GAVE_NOTHING, null, null, null), null,
                 new StepAnswers.ErroredForAnswer("gave_nothing", null, null, null, null, null)],
                [codeFault(CodeErrorReason.NOT_DECLARED, null, new CodeError.UndeclaredMember("", false), null), '{"":1}',
                 new StepAnswers.ErroredForAnswer("not_declared", null, "", null, null, null)],
                [codeFault(CodeErrorReason.NOT_DECLARED, "lines", new CodeError.UndeclaredMember("n" * 64, false), null),
                 '{"lines":[{"' + "n" * 64 + '":1}]}',
                 new StepAnswers.ErroredForAnswer("not_declared", "lines", "n" * 64, null, null, null)],
                [codeFault(CodeErrorReason.NOT_DECLARED, "lines", new CodeError.UndeclaredMember("n" * 65, false), null),
                 '{"lines":[{"' + "n" * 65 + '":1}]}',
                 new StepAnswers.ErroredForAnswer("not_declared", "lines", "n" * 64, true, null, null)],
                [codeFault(CodeErrorReason.GIVES_OTHERWISE, "receipt", null, new CodeError.StepReads(stepId(2).value())),
                 null, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null, null,
                        new StepAnswers.ReadByAnswer(stepId(2).value(), null), null)],
                [codeFault(CodeErrorReason.GIVES_OTHERWISE, "receipt", null,
                        new CodeError.OutputReads([new FieldName("result"), new FieldName("receipt")])),
                 null, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null, null,
                        new StepAnswers.ReadByAnswer(null, "result.receipt"), null)],
        ]].combinations().collect { [it[0]] + it[1] }
    }

    private static CodeError.Fault codeFault(
            CodeErrorReason reason, String field, CodeError.UndeclaredMember member, CodeError.ReadBy readBy) {
        new CodeError.Fault(reason, field == null ? [] : Pointer.parse(field).names(), member, readBy)
    }

    def "every time a call to produce a try was turned away is on that try, oldest first, what the model said withheld as the rest of it is"() {
        given:
        def held = run([started(question(1, model(), 3), [sent(1, null, [2, 1])],
                new HoldRecord(RunStepHoldReason.TURNED_AWAY, minutes(20), attemptId(1), false, null))])
        def turned = [(tryId(1)): [new StepTries.TurnawayRecord(attemptId(2), minutes(11), null, false, true),
                                   new StepTries.TurnawayRecord(attemptId(1), minutes(12), "Too busy.", false, false)]]

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
                        new StepAnswers.FromAnswer("step", "answer", stepId(1).value(), "step_1", null, null, "tidy", null, null, null)),
                new StepAnswers.TakesFromAnswer("from_question",
                        new StepAnswers.FromAnswer("step", "answer", stepId(2).value(), "step_2", null, key(202), null, null, null, null)),
                new StepAnswers.TakesFromAnswer("from_workflow",
                        new StepAnswers.FromAnswer("step", "answer", stepId(3).value(), "step_3", null, key(203), null, null, null, null))]

        and: "the code step declared by its name beside each version a question pins, and no workflow a step runs"
        read.declarations().keySet() as List == [VERSION.value().toString(), "tidy", key(202).toString(),
                                                 key(204).toString()]
        read.declarations()["tidy"].gives()*.name() == ["answer"]
        read.declarations()["tidy"].takes() == []
    }

    def "step rows and input sources carry each planned display name without changing pinned declarations or machine paths"() {
        given:
        def originals = [codeStep(1, 1), question(2, person(), 1), workflowStep(3), TAKES_FROM_EACH]
        def labels = ["記録を読む", "回答を確かめる\u202e", "処理を続ける", "まとめを受け取る"]
        def labelled = originals.withIndex().collect { planned, index ->
            new PlannedStep(planned.id(), planned.order(), planned.name(),
                    new com.lilradish.lite.domain.workflow.StepLabel(labels[index]), planned.runs(),
                    planned.producer(), planned.tries(), planned.reviewer(), planned.bindings())
        }

        when:
        def read = reading(run(labelled.collect { unstarted(it) }), READS_MODELS).steps()

        then:
        read.steps()*.stepId() == (1..4).collect { stepId(it).value() }
        read.steps()*.name() == ["step_1", "step_2", "step_3", "step_4"]
        read.steps()*.label() == ["記録を読む", "回答を確かめる\u202e", "処理を続ける", "まとめを受け取る"]
        read.steps()[3].takesFrom() == [
                new StepAnswers.TakesFromAnswer("from_code", new StepAnswers.FromAnswer("step", "answer",
                        stepId(1).value(), "step_1", "記録を読む", null, "tidy", null, null, null)),
                new StepAnswers.TakesFromAnswer("from_question", new StepAnswers.FromAnswer("step", "answer",
                        stepId(2).value(), "step_2", "回答を確かめる\u202e", key(202), null, null, null, null)),
                new StepAnswers.TakesFromAnswer("from_workflow", new StepAnswers.FromAnswer("step", "answer",
                        stepId(3).value(), "step_3", "処理を続ける", key(203), null, null, null, null))]
        read.declarations().keySet() as List == [VERSION.value().toString(), "tidy", key(202).toString(), key(204).toString()]
        labelled[3].bindings() == TAKES_FROM_EACH.bindings()
    }

    def "an input taken from a search names its pinned question and search way, and no code step"() {
        given:
        def takesTitle = questionTaking(2, takes([text("from_search")]), [new Binding(key(831),
                Pointer.parse("from_search"), new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("title")))])

        when:
        def read = reading(run([unstarted(searchStep(1)), unstarted(takesTitle)]), READS_MODELS).steps()

        then:
        read.steps()[1].takesFrom() == [new StepAnswers.TakesFromAnswer("from_search",
                new StepAnswers.FromAnswer("step", "title", stepId(1).value(), "step_1", null, key(201), null, null, null, "web_search"))]
    }

    def "a search step reads its pinned question identity and way, naming no code step"() {
        when:
        def read = reading(run([unstarted(searchStep(1))]), READS_MODELS).steps()

        then:
        read.steps()[0].runs() == new StepAnswers.RunsAnswer("search", key(101), "Demo Sample", 1, key(201), null, "web_search", "search", null)
    }

    def "a search step not reached offers no form"() {
        given:
        def searching = unstarted(searchStep(1))
        def stepReading = reading(run([searching, unstarted(question(2, person(), 1))]), READS_MODELS)

        expect:
        stepReading.answering(searching, 0, [:]) == null
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
    def "a held model production names its actual code source even without the release or permission to read model content"() {
        given:
        def sourceStep = started(codeStep(1, 1, null),
                [yielded(1, StepProducer.PERSON, [new ValueRecord(valueId(3), "answer", json("Demo Sample"), null, false)])])
        def template = run([sourceStep])
        def source = new HoldRecord.SourceRecord(template.run(), sourceStep.runStep(), stepId(1).value())
        def held = started(question(2, model(), 2), [],
                new HoldRecord(RunStepHoldReason.CODE_STEP_NOT_HELD, minutes(20), null, false, source))
        def snapshot = run([sourceStep, held])

        when:
        def read = reading(snapshot, permitted)
        def listed = read.steps().steps()[1]
        def detail = read.step(stepId(2)).step()

        then:
        listed.state() == "held_back"
        listed.where().reason() == "code_step_not_held"
        listed.where().source() == new StepAnswers.HeldSourceAnswer(
                template.run().value(), 7, stepId(1).value(), "step_1", null, "tidy")
        detail.where().source() == listed.where().source()
        listed.cost().callsAModel()
        listed.tries().current() == 0
        !read.steps().declarations().containsKey("tidy")

        where:
        permitted << [READS_MODELS, READS_NO_MODEL]
    }

    def "a source hold names the child run that produced the code value rather than the parent workflow or held step"() {
        given:
        def originalSource = codeStep(1, 1, null)
        def sourcePlan = new PlannedStep(originalSource.id(), originalSource.order(), originalSource.name(),
                label == null ? null : new com.lilradish.lite.domain.workflow.StepLabel(label), originalSource.runs(),
                originalSource.producer(), originalSource.tries(), originalSource.reviewer(), originalSource.bindings())
        def sourceStep = started(sourcePlan, [yielded(1, StepProducer.PERSON, [new ValueRecord(valueId(3), "answer", json("Demo Sample"), null, false)])])
        def template = run([sourceStep])
        def child = new RunSnapshot(new RunId(key(2401)), template.root(), template.group(), template.version(),
                false, null, null, template.workflow(), template.steps())
        def parent = new StepSnapshot(workflowStep(1), null, new RunStepId(key(2402)), null, [], [],
                new StepSnapshot.Beneath(child, null), [:])
        def held = started(question(2, model(), 2), [],
                new HoldRecord(RunStepHoldReason.CODE_STEP_NOT_HELD, minutes(20), null, false,
                        new HoldRecord.SourceRecord(child.run(), sourceStep.runStep(), stepId(1).value())))
        def details = Mockito.mock(RunReadDetails)
        Mockito.when(details.costBeneath(Mockito.anyList())).thenReturn([(child.run()): RunBudget.Spend.NOTHING])
        Mockito.when(details.numbered(child.run())).thenReturn(new RunReadDetails.NumberedRunRecord(child.run(), 8))
        Mockito.when(details.beneath(parent)).thenReturn(new RunReadDetails.Beneath(
                new RunReadDetails.NumberedRunRecord(child.run(), 8),
                new RunReadDetails.Workflow(key(2403), "Demo Sample", 1, child.version().value(), null), null))

        when:
        def read = reading(run([parent, held]), READS_NO_MODEL).withDetails(details).steps()

        then:
        read.steps()[1].where().source() == new StepAnswers.HeldSourceAnswer(
                child.run().value(), 8, stepId(1).value(), "step_1", label, "tidy")
        read.steps()[1].where().source().runId() != template.run().value()
        read.steps()[1].where().source().stepId() != held.planned().id().value()

        where:
        label << [null, "子の記録を読む\u202e"]
    }

    def "a stored source failure names the actual code maker and original field without looking up a removed declaration"() {
        given:
        def sourceStep = started(codeStep(1, 1, null), [yielded(1, StepProducer.PERSON, [new ValueRecord(valueId(3), "answer", json("Demo Sample"), null, false)])])
        def template = run([sourceStep])
        def failure = new FailureRecord(new RunStepFailureId(key(2451)),
                reason, tryId(2), ModelCallPurpose.PRODUCE, minutes(20),
                false, null, null, new FailureRecord.SourceRecord(
                        new HoldRecord.SourceRecord(template.run(), sourceStep.runStep(), stepId(1).value()), "removed.field"))
        def failed = started(question(2, model(), 2), [new TryRecord(tryId(2), null, StepProducer.MODEL, null, askedAt(2), null, null, null,
                null, null, false, null, false, false, null, [], [], [], [], [])], null, [failure])
        def snapshot = run([sourceStep, failed])

        when:
        def read = reading(snapshot, permitted)
        def listed = read.steps().steps()[1]
        def detail = read.step(stepId(2)).step()

        then:
        listed.state() == "failed"
        listed.where().reason() == reason.published()
        listed.where().source() == null
        def named = new StepAnswers.FailedSourceAnswer(
                new StepAnswers.HeldSourceAnswer(template.run().value(), 7, stepId(1).value(), "step_1", null, "tidy"),
                "removed.field")
        listed.where().failedSourceNoLongerMatches() ==
                (reason == RunStepFailureReason.SOURCE_NO_LONGER_MATCHES ? named : null)
        listed.where().failedSourceListNotHere() ==
                (reason == RunStepFailureReason.SOURCE_LIST_NOT_HERE ? named : null)
        detail.where().failedSourceNoLongerMatches() == listed.where().failedSourceNoLongerMatches()
        detail.where().failedSourceListNotHere() == listed.where().failedSourceListNotHere()
        listed.cost().sent() == "0"
        listed.tries().current() == 0
        listed.next() == null
        listed.stateActs() == ["try_sending"]
        !read.steps().declarations().containsKey("tidy")

        where:
        reason | permitted
        RunStepFailureReason.SOURCE_NO_LONGER_MATCHES | READS_MODELS + [GroupPermission.START_RUN]
        RunStepFailureReason.SOURCE_NO_LONGER_MATCHES | READS_NO_MODEL + [GroupPermission.START_RUN]
        RunStepFailureReason.SOURCE_LIST_NOT_HERE | READS_MODELS + [GroupPermission.START_RUN]
        RunStepFailureReason.SOURCE_LIST_NOT_HERE | READS_NO_MODEL + [GroupPermission.START_RUN]
    }

    def "a corrupt source identity is refused instead of being attributed to an unreached or different step"() {
        given:
        def sourceStep = started(codeStep(1, 1, null), [yielded(1, StepProducer.PERSON, [BY_HAND])])
        def template = run([sourceStep])
        def source = new HoldRecord.SourceRecord(
                wrongRun ? new RunId(key(2411)) : template.run(),
                wrongReached ? new RunStepId(key(2412)) : sourceStep.runStep(),
                wrongPlanned ? stepId(9).value() : stepId(1).value())
        def held = started(question(2, model(), 2), [],
                new HoldRecord(RunStepHoldReason.CODE_STEP_NOT_HELD, minutes(20), null, false, source))

        when:
        reading(run([sourceStep, held]), READS_NO_MODEL).steps()

        then:
        thrown(IllegalStateException)

        where:
        wrongRun | wrongReached | wrongPlanned
        true     | false        | false
        false    | true         | false
        false    | false        | true
    }

    def "a produced search try rejects the whole input collection when any binding lacks its unique planned identity"() {
        given:
        def source = new BindingSource.WorkflowInput(Pointer.parse("category"))
        def one = new Binding(key(2201), Pointer.parse("first"), source)
        def two = new Binding(key(2202), Pointer.parse("second"), source)
        def planned = searchStep(1, com.lilradish.lite.domain.run.fixture.Runs.webSearch(),
                searchQuestion(1, com.lilradish.lite.domain.run.fixture.Runs.webSearch(),
                        takes([text("first"), text("second")])), repeatedPlan ? [one, one] : [one, two])
        def inputs = switch (shape) {
            case "missing" -> [new InputRecord(one.id(), null, [])]
            case "duplicate" -> [new InputRecord(one.id(), null, []), new InputRecord(one.id(), null, [])]
            case "foreign" -> [new InputRecord(one.id(), null, []), new InputRecord(key(2204), null, [])]
            case "extra" -> [new InputRecord(one.id(), null, []), new InputRecord(two.id(), null, []),
                             new InputRecord(key(2203), null, [])]
            default -> [new InputRecord(one.id(), null, [])]
        }
        def tried = new TryRecord(tryId(2201), 1, StepProducer.PERSON, null, askedAt(1), endedAt(1),
                PERSON, null, null, null, false, null, false, false, null,
                [value(2201, "title", false, "Fictional report"), value(2202, "body", false, "Invented findings.")], [], inputs, [], [])
        def snapshot = run([started(planned, [tried])], false, null, [category: "Demo Sample"])

        when:
        reading(snapshot, READS_MODELS).step(stepId(1))

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "Try " + tried.id().value() + " " + reason

        where:
        shape       | repeatedPlan || reason
        "missing"   | false        || "has no input for binding " + key(2202)
        "duplicate" | false        || "repeats binding " + key(2201)
        "foreign"   | false        || "has no input for binding " + key(2202)
        "extra"     | false        || "holds an unplanned input"
        "missing"   | true         || "has no input for binding " + key(2201)
    }

}
