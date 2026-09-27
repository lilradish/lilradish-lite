package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.inference.Askings.held
import static org.lilradish.lite.testutil.inference.Askings.text

import org.lilradish.lite.domain.declaration.Asking
import org.lilradish.lite.domain.declaration.Instruction
import org.lilradish.lite.domain.inference.SendMeasure
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import spock.lang.Specification

/** What a step could send its models, held to what the measure counts and what the deployment's models take. */
class SendPastSpec extends Specification {

    static final EntryVersionId ASKED = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000f61"))

    static final EntryVersionId UNASKED = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000f62"))

    static final UUID STEP = UUID.fromString("0000000c-0000-4000-8000-000000000f61")

    static final UUID LATER = UUID.fromString("0000000c-0000-4000-8000-000000000f62")

    static final ModelName TINY = new ModelName("tiny")

    static final ModelName VAST = new ModelName("vast")

    static final ModelName UNHELD = new ModelName("unheld")

    /** Two characters to a unit, so the units counted are not the characters measured. */
    static final ModelCatalog MODELS = new ModelCatalog([
            new DeployedModel(TINY, [], 10, 2.0G, 10, []),
            new DeployedModel(VAST, [], 1_000_000_000, 2.0G, 10, [])])

    static final Asking ASKING = new Asking(new Instruction("Say what it is."), [text("letter", 20)], [text("summary", 5)])

    def "what is said is past by one unit at least, and names its step, what it asks and the model"() {
        when:
        new SendPast(step, role, model, past)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        step | role                  | model | past || expected                 | message
        STEP | SendingRole.PRODUCING | TINY  | 0    || IllegalArgumentException | "SendPast is past by one unit at least: 0"
        null | SendingRole.PRODUCING | TINY  | 1    || NullPointerException     | "SendPast step must not be null"
        STEP | null                  | TINY  | 1    || NullPointerException     | "SendPast role must not be null"
        STEP | SendingRole.PRODUCING | null  | 1    || NullPointerException     | "SendPast model must not be null"
    }

    def "a step asking a question is said past each model it asks, by the units that model counts past its limit"() {
        given:
        def step = asking(STEP, producer, reviewer == null ? null : new ModelChoice(reviewer, null))

        when:
        def found = SendPast.of([step], [(ASKED): ASKING], MODELS)

        then:
        found == said.collect { role, told -> pastOf(STEP, role, TINY, told) }

        where:
        producer                                        | reviewer || said
        new Producer.Model(new ModelChoice(TINY, null), true)   | TINY     || [[SendingRole.PRODUCING, true], [SendingRole.REVIEWING, true]]
        new Producer.Model(new ModelChoice(TINY, null), false)  | null     || [[SendingRole.PRODUCING, false]]
        new Producer.Model(new ModelChoice(TINY, null), false)  | TINY     || [[SendingRole.PRODUCING, false], [SendingRole.REVIEWING, true]]
        new Producer.Model(new ModelChoice(VAST, null), true)   | TINY     || [[SendingRole.REVIEWING, true]]
        new Producer.Person()                                   | TINY     || [[SendingRole.REVIEWING, true]]
        new Producer.Model(new ModelChoice(VAST, null), true)   | VAST     || []
        new Producer.Model(new ModelChoice(UNHELD, null), true) | UNHELD   || []
        new Producer.Person()                                   | null     || []
    }

    /**
     * The limit set at the units the step could send, one below them, and, four characters to a unit, one below
     * the characters it could send, which is still no fewer than its units; the producer is a model not held.
     */
    def "a step is said past a model exactly where that model takes fewer units than the step could send"() {
        given:
        long sent = role == SendingRole.REVIEWING
                ? SendMeasure.mostSentToReview(ASKING)
                : SendMeasure.mostSent(ASKING, true)
        long limit = belowUnits == null ? sent - 1 : Math.ceilDiv(sent, perUnit.longValue()) - belowUnits
        def models = new ModelCatalog([new DeployedModel(TINY, [], limit, perUnit, 10, [])])
        def step = role == SendingRole.REVIEWING
                ? asking(STEP, new Producer.Model(new ModelChoice(UNHELD, null), true), new ModelChoice(TINY, null))
                : asking(STEP, new Producer.Model(new ModelChoice(TINY, null), true), null)

        when:
        def found = SendPast.of([step], [(ASKED): ASKING], models)

        then:
        found == (past == 0 ? [] : [new SendPast(STEP, role, TINY, past)])

        where:
        role                  | perUnit | belowUnits || past
        SendingRole.PRODUCING | 2.0G    | 0          || 0
        SendingRole.PRODUCING | 2.0G    | 1          || 1
        SendingRole.PRODUCING | 4.0G    | null       || 0
        SendingRole.REVIEWING | 2.0G    | 0          || 0
        SendingRole.REVIEWING | 2.0G    | 1          || 1
        SendingRole.REVIEWING | 4.0G    | null       || 0
    }

    /** Only a question a step asks is measured, and only one whose asking could be worked out. */
    def "a step asking no question, or one that could not be told, is never said"() {
        given:
        def model = new Producer.Model(new ModelChoice(TINY, null), true)
        def reviewer = new ModelChoice(TINY, null)

        when:
        def found = SendPast.of([
                new StoredWorkflow.Step(STEP, new StepId("unchosen"), new StoredWorkflow.Runs.Unchosen(), model, 1,
                        reviewer, []),
                new StoredWorkflow.Step(STEP, new StepId("code"), new StoredWorkflow.Runs.Code("send_reply"),
                        new Producer.Code(), 1, reviewer, []),
                new StoredWorkflow.Step(STEP, new StepId("led"), new StoredWorkflow.Runs.Pinned(EntryKind.WORKFLOW, ASKED),
                        model, 1, reviewer, []),
                new StoredWorkflow.Step(STEP, new StepId("untold"),
                        new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, UNASKED), model, 1, reviewer, [])],
                [(ASKED): ASKING], MODELS)

        then:
        found == []
    }

    def "steps are said in the order they run"() {
        given:
        def model = new Producer.Model(new ModelChoice(TINY, null), false)

        when:
        def found = SendPast.of([asking(STEP, model, null), asking(LATER, model, null)], [(ASKED): ASKING], MODELS)

        then:
        found == [pastOf(STEP, SendingRole.PRODUCING, TINY, false), pastOf(LATER, SendingRole.PRODUCING, TINY, false)]
    }

    /** Past counting, the figure said is the one a page takes for no count at all, producing or reviewing alike. */
    def "a step whose most could run past what a long counts is said past by all a long holds"() {
        given:
        def rows = held("rows", [held("cells", [text("cell", Integer.MAX_VALUE, Integer.MAX_VALUE)], Integer.MAX_VALUE)],
                Integer.MAX_VALUE)
        def vast = new Asking(new Instruction("Say what it is."), [rows], [rows])

        when:
        def found = SendPast.of(
                [asking(STEP, new Producer.Model(new ModelChoice(TINY, null), told), new ModelChoice(TINY, null))],
                [(ASKED): vast], MODELS)

        then:
        found == [new SendPast(STEP, SendingRole.PRODUCING, TINY, Long.MAX_VALUE),
                  new SendPast(STEP, SendingRole.REVIEWING, TINY, Long.MAX_VALUE)]

        where:
        told << [true, false]
    }

    private static StoredWorkflow.Step asking(UUID id, Producer producer, ModelChoice reviewer) {
        new StoredWorkflow.Step(id, new StepId("classify"), new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, ASKED),
                producer, 1, reviewer, [])
    }

    /**
     * Counted afresh from the measure and the model, rounding up as the model's own count does; a review is
     * measured as though told, whatever {@code told} a row names for it.
     */
    private static SendPast pastOf(UUID id, SendingRole role, ModelName name, boolean told) {
        def model = MODELS.find(name).get()
        long sent = role == SendingRole.REVIEWING
                ? SendMeasure.mostSentToReview(ASKING)
                : SendMeasure.mostSent(ASKING, told)
        new SendPast(id, role, name, Math.ceilDiv(sent, 2L) - model.sentPerCallLimit())
    }
}
