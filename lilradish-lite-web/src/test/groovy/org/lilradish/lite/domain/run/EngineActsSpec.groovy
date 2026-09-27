package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.MODEL
import static org.lilradish.lite.domain.run.fixture.Runs.attemptId
import static org.lilradish.lite.domain.run.fixture.Runs.codeStep
import static org.lilradish.lite.domain.run.fixture.Runs.hold
import static org.lilradish.lite.domain.run.fixture.Runs.lost
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.model
import static org.lilradish.lite.domain.run.fixture.Runs.modelReview
import static org.lilradish.lite.domain.run.fixture.Runs.open
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.position
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.routeStep
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.started
import static org.lilradish.lite.domain.run.fixture.Runs.stepId
import static org.lilradish.lite.domain.run.fixture.Runs.stop
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.workflowStep
import static org.lilradish.lite.domain.run.fixture.Runs.yielded

import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.run.fixture.Runs
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class EngineActsSpec extends Specification {

    static final StopRecord STOP = stop(minutes(100))

    def "a stopped run starts nothing, whatever its first step not done could do"() {
        given:
        def stopped = run([step("a person's question", 1, null)], true)

        expect:
        EngineActs.next(stopped, [position(at)]) == new EngineAct.Nothing()

        where:
        at << ["not started", "running its next try", "held back on a stop", "awaiting the model's review"]
    }

    /** A person's question held so reads as the try it owes, not as held back, and is released all the same. */
    def "a step held on a stop that has since been let go is released, the steps done before it passed over"() {
        given:
        def first = unstarted(question(1, person(), 1))
        def held = held(runs, 2, null, RunStepHoldReason.ENTRY_STOPPED)

        expect:
        EngineActs.next(run([first, held]), [position("done"), position(at)]) == new EngineAct.ReleaseHold(held.planned())

        where:
        runs                  | at
        "a person's question" | "asked"
        "a model's question"  | "held back on a stop before it was asked"
        "code"                | "held back on a stop before it was asked"
        "a workflow"          | "held back on a stop before it was asked"
    }

    def "a step held on a stop still in force, or held for another reason, is left as it is"() {
        given:
        def held = held("a person's question", 1, stop == "the entry's" ? STOP : null, reason)

        expect:
        EngineActs.next(run([held], false, stop == "the workflow's" ? STOP : null), [position(at)]) ==
                new EngineAct.Nothing()

        where:
        at                                        | reason                          | stop
        "held back on a stop before it was asked" | RunStepHoldReason.ENTRY_STOPPED | "the workflow's"
        "held back on a stop before it was asked" | RunStepHoldReason.ENTRY_STOPPED | "the entry's"
        "held back too long"                      | RunStepHoldReason.TOO_LONG      | "none"
        "held back, turned away"                  | RunStepHoldReason.TURNED_AWAY   | "none"
    }

    def "a stop let go is released only from a hold the step holds, never from a position reading as held back"() {
        given:
        def unheld = step("a person's question", 1, null)

        expect:
        EngineActs.next(run([unheld]), [position("held back on a stop")]) == new EngineAct.Nothing()
    }

    def "a new try a stop is in force for, on the workflow or on what the step runs, is held"() {
        given:
        def next = step(runs, 1, stop == "the entry's" ? STOP : null)

        expect:
        EngineActs.next(run([next], false, stop == "the workflow's" ? STOP : null), [position(at)]) ==
                new EngineAct.HoldOnStop(next.planned())

        where:
        [at, stop, runs] << [
                ["not started", "running its next try"],
                ["the workflow's", "the entry's"],
                ["a person's question", "a model's question", "code"],
        ].combinations()
    }

    def "a code step code produces, reached with no try or due its next, is started on that try"() {
        given:
        def code = started(codeStep(1, 3, Runs.tidy(true)), (1..<number).collect { lost(it, StepProducer.CODE) })

        expect:
        EngineActs.next(run([code]), [position(at)]) == new EngineAct.StartTry(code.planned(), number)

        where:
        at                     | number
        "not started"          | 1
        "running its next try" | 1
        "running its next try" | 2
    }

    def "a code step whose code is out, or whose next try is a person's, starts nothing"() {
        given:
        def code = started(codeStep(1, 3, Runs.tidy(true)), [lost(1, StepProducer.CODE)])

        expect:
        EngineActs.next(run([code]), [position(at)]) == new EngineAct.Nothing()

        where:
        at << ["running code", "owed", "failed with its tries spent", "held back, code step not held"]
    }

    /** No try is spent on it: nothing went wrong in code the release does not hold. */
    def "a code step the release does not hold is held rather than started, whoever it is asked of"() {
        given:
        def code = unstarted(codeStep(1, 2, null, producer))

        expect:
        EngineActs.next(run([code]), [position(at)]) == new EngineAct.HoldNotHeld(code.planned())

        where:
        [at, producer] << [["not started", "running its next try"], [Runs.code(), person()]].combinations()
    }

    def "a stop in force holds a code step's next try ahead of the release not holding it"() {
        given:
        def code = unstarted(codeStep(1, 2, released ? Runs.tidy(false) : null), STOP)

        expect:
        EngineActs.next(run([code]), [position("running its next try")]) == new EngineAct.HoldOnStop(code.planned())

        where:
        released << [true, false]
    }

    /** The release is read afresh each time, so a release holding it again lets it go on by itself. */
    def "a code step held for the release not holding it is released once the release holds it, and left until then"() {
        given:
        def code = started(codeStep(1, 2, released ? Runs.tidy(false) : null), [],
                hold(RunStepHoldReason.CODE_STEP_NOT_HELD, minutes(40)))

        expect:
        EngineActs.next(run([code]), [position("held back, code step not held")]) ==
                (released ? new EngineAct.ReleaseHold(code.planned()) : new EngineAct.Nothing())

        where:
        released << [true, false]
    }

    def "a code step a person produces, not yet started, is asked of its first try as a person's question is"() {
        given:
        def code = unstarted(codeStep(1, 2, Runs.tidy(false), person()))

        expect:
        EngineActs.next(run([code]), [position("not started")]) == new EngineAct.StartTry(code.planned(), 1)
    }

    def "a person's question not yet started, with no stop on it, is asked of its first try"() {
        given:
        def steps = (0..<before).collect { unstarted(question(it + 1, person(), 1)) } +
                [step("a person's question", before + 1, null)]
        def positions = (0..<before).collect { position("done") } + [position("not started")]

        expect:
        EngineActs.next(run(steps), positions) == new EngineAct.StartTry(steps.last().planned(), 1)

        where:
        before << [0, 2]
    }

    def "a person's question whose last try the model reviewing it refused is asked of its next try"() {
        given:
        def answer = value(1, "answer", true)
        def refusedOnce = started(question(1, person(), 3, MODEL), [yielded(1, StepProducer.PERSON, [answer],
                [modelReview(minutes(30), [refused(answer)])])])

        expect:
        EngineActs.next(run([refusedOnce]), [position("running its next try")]) ==
                new EngineAct.StartTry(refusedOnce.planned(), 2)
    }

    def "a model's question reached with no try, or due its next try, is asked of that try by the system"() {
        given:
        def steps = (0..<before).collect { unstarted(question(it + 1, person(), 1)) } +
                [started(question(before + 1, model(), 3), (1..<number).collect { lost(it, StepProducer.MODEL) })]
        def positions = (0..<before).collect { position("done") } + [position(at)]

        expect:
        EngineActs.next(run(steps), positions) == new EngineAct.StartTry(steps.last().planned(), number)

        where:
        [at, number, before] << [["not started", "running its next try"], [1, 2], [0, 1]].combinations()
    }

    def "a model's open try with nothing sent for it is sent, and no try is asked in its place"() {
        given:
        def asked = started(question(1, model(), 3), (1..<number).collect { lost(it, StepProducer.MODEL) } +
                [open(number, StepProducer.MODEL)])

        expect:
        EngineActs.next(run([asked]), [position("running its next try")]) == new EngineAct.Send(asked.planned(), number)

        where:
        number << [1, 2]
    }

    /** A stop holds what the step would do next, whether that is asking a try or sending one already asked. */
    def "a model's open try with nothing sent is held on a stop in force rather than sent"() {
        given:
        def asked = started(question(1, model(), 2), [open(1, StepProducer.MODEL)], null, [],
                stop == "the entry's" ? STOP : null)

        expect:
        EngineActs.next(run([asked], false, stop == "the workflow's" ? STOP : null), [position("running its next try")]) ==
                new EngineAct.HoldOnStop(asked.planned())

        where:
        stop << ["the workflow's", "the entry's"]
    }

    def "a step running a call whose try was sent already, or is not a model's, is left to the call"() {
        given:
        def asked = started(question(1, producer == StepProducer.MODEL ? model() : person(), 2), [newest])

        expect:
        EngineActs.next(run([asked]), [position("running a call")]) == new EngineAct.Nothing()

        where:
        producer            | newest
        StepProducer.MODEL  | sent(open(1, StepProducer.MODEL), true)
        StepProducer.MODEL  | sent(open(1, StepProducer.MODEL), false)
        StepProducer.PERSON | open(1, StepProducer.PERSON)
    }

    def "a step running a workflow or a route, or code a model would produce, is left to what runs it"() {
        expect:
        EngineActs.next(run([step(runs, 1, null)]), [position(at)]) == new EngineAct.Nothing()

        where:
        at                     | runs
        "not started"          | "code a model produces"
        "not started"          | "a workflow"
        "not started"          | "a route"
        "running its next try" | "code a model produces"
    }

    def "a first step not done that a person, a review, something out, or a failure owns leaves the run to it"() {
        given:
        def steps = [step("a person's question", 1, null), step("a person's question", 2, null)]

        expect:
        EngineActs.next(run(steps), [position(at), position("not started")]) == new EngineAct.Nothing()

        where:
        at << ["asked", "owed", "awaiting a person's review", "awaiting the model's review, out",
               "awaiting the model's review, turned away", "awaiting a person's review in the model's place",
               "running code", "running a call", "failed with its tries spent", "failed as written down",
               "held back too long"]
    }

    /** What was produced may still be reviewed while what the step runs, or the workflow, is stopped. */
    def "a question's values waiting on the model it names, nothing sent for them, are sent to it, whatever stop holds the step"() {
        given:
        def produced = started(question(1, model(), 2, MODEL), [yielded(1, StepProducer.MODEL, [value(1, "answer", true)])],
                null, [], stop == "the entry's" ? STOP : null)
        def second = step("a person's question", 2, null)

        expect:
        EngineActs.next(run([produced, second], false, stop == "the workflow's" ? STOP : null),
                [position("awaiting the model's review"), position("not started")]) ==
                new EngineAct.Review(produced.planned(), 1)

        where:
        stop << ["none", "the entry's", "the workflow's"]
    }

    /** Sent as the running release declares the code step, so not while it declares none. */
    def "a code step's values waiting on the model it names are sent to it only while the release holds the code step"() {
        given:
        def code = started(new PlannedStep(stepId(1), 1, new StepId("step_1"),
                new StepRuns.Code("tidy", held ? Runs.tidy(true) : null), Runs.code(), 2, MODEL, []),
                [yielded(1, StepProducer.CODE, [value(1, "answer", true)])])

        expect:
        EngineActs.next(run([code]), [position("awaiting the model's review")]) ==
                (held ? new EngineAct.Review(code.planned(), 1) : new EngineAct.Nothing())

        where:
        held << [true, false]
    }

    def "a run whose every step is done has nothing left to do"() {
        given:
        def steps = [step("a person's question", 1, null), step("code", 2, null)]

        expect:
        EngineActs.next(run(steps), [position("done"), position("done")]) == new EngineAct.Nothing()
    }

    def "what a run does next cannot be worked out from other than one position per step"() {
        when:
        EngineActs.next(run([step("a person's question", 1, null)]), [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "EngineActs was handed other than one position per step"
    }

    private static StepSnapshot step(String runs, int order, StopRecord pinStopped) {
        switch (runs) {
            case "a person's question": return unstarted(question(order, person(), 2), pinStopped)
            case "a model's question": return unstarted(question(order, model(), 2), pinStopped)
            case "code": return unstarted(codeStep(order, 2), pinStopped)
            case "code a model produces": return unstarted(producedCode(order, model()), pinStopped)
            case "code a person produces": return unstarted(producedCode(order, person()), pinStopped)
            case "a workflow": return unstarted(workflowStep(order), pinStopped)
            case "a route": return unstarted(routeStep(order), pinStopped)
            default: throw new IllegalArgumentException(runs)
        }
    }

    private static StepSnapshot held(String runs, int order, StopRecord pinStopped, RunStepHoldReason reason) {
        started(step(runs, order, null).planned(), [], hold(reason, minutes(40)), [], pinStopped)
    }

    private static PlannedStep producedCode(int order, Producer producer) {
        new PlannedStep(stepId(order), order, new StepId("step_" + order), new StepRuns.Code("tidy", Runs.tidy(false)),
                producer, 2, null, [])
    }

    /** {@code open} with an attempt to produce it made, and its call written down where the attempt was not too long. */
    private static TryRecord sent(TryRecord open, boolean called) {
        new TryRecord(open.id(), open.number(), open.producer(), null, open.askedAt(), null, null, null, null, null,
                null, false, null, [], [], [],
                [new AttemptRecord(attemptId(1), ModelCallPurpose.PRODUCE, !called, null)],
                called ? [new CallRecord(new ModelCallId(Runs.key(1100)), attemptId(1), null, false)] : [])
    }
}
