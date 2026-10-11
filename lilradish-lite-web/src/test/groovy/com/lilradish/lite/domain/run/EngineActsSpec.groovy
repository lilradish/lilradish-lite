package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.fixture.Runs.MODEL
import static com.lilradish.lite.domain.run.fixture.Runs.attemptId
import static com.lilradish.lite.domain.run.fixture.Runs.codeStep
import static com.lilradish.lite.domain.run.fixture.Runs.hold
import static com.lilradish.lite.domain.run.fixture.Runs.lost
import static com.lilradish.lite.domain.run.fixture.Runs.minutes
import static com.lilradish.lite.domain.run.fixture.Runs.model
import static com.lilradish.lite.domain.run.fixture.Runs.modelReview
import static com.lilradish.lite.domain.run.fixture.Runs.open
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.position
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.refused
import static com.lilradish.lite.domain.run.fixture.Runs.routeStep
import static com.lilradish.lite.domain.run.fixture.Runs.run
import static com.lilradish.lite.domain.run.fixture.Runs.started
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.stop
import static com.lilradish.lite.domain.run.fixture.Runs.unstarted
import static com.lilradish.lite.domain.run.fixture.Runs.value
import static com.lilradish.lite.domain.run.fixture.Runs.workflowStep
import static com.lilradish.lite.domain.run.fixture.Runs.yielded

import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.run.fixture.Runs
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class EngineActsSpec extends Specification {

    static final StopRecord STOP = stop(minutes(100))

    /** Deep enough that no run of these trees reaches it. */
    static final int DEEP_ENOUGH = 32

    def "a stopped run starts nothing, whatever its first step not done could do"() {
        given:
        def stopped = run([step("a person's question", 1, null)], true)

        expect:
        EngineActs.next(RunInTree.root(stopped), DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)
        EngineActs.next(RunInTree.root(stopped), [position(at)], DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)

        where:
        at << ["not started", "running its next try", "held back on a stop", "awaiting the model's review"]
    }

    /** A person's question held so reads as the try it owes, not as held back, and is released all the same. */
    def "a step held on a stop that has since been let go is released, the steps done before it passed over"() {
        given:
        def first = unstarted(question(1, person(), 1))
        def held = held(runs, 2, null, RunStepHoldReason.ENTRY_STOPPED)

        expect:
        EngineActs.next(RunInTree.root(run([first, held])), [position("done"), position(at)], DEEP_ENOUGH) == new EngineAct.ReleaseHold(Runs.RUN, held.planned())

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
        EngineActs.next(RunInTree.root(run([held], false, stop == "the workflow's" ? STOP : null)), [position(at)], DEEP_ENOUGH) ==
                new EngineAct.Nothing(Runs.RUN)

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
        EngineActs.next(RunInTree.root(run([unheld])), [position("held back on a stop")], DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)
    }

    def "a new try a stop is in force for, on the workflow or on what the step runs, is held"() {
        given:
        def next = step(runs, 1, stop == "the entry's" ? STOP : null)

        expect:
        EngineActs.next(RunInTree.root(run([next], false, stop == "the workflow's" ? STOP : null)), [position(at)], DEEP_ENOUGH) ==
                new EngineAct.HoldOnStop(Runs.RUN, next.planned())

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
        EngineActs.next(RunInTree.root(run([code])), [position(at)], DEEP_ENOUGH) == new EngineAct.StartTry(Runs.RUN, code.planned(), number)

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
        EngineActs.next(RunInTree.root(run([code])), [position(at)], DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)

        where:
        at << ["running code", "owed", "failed with its tries spent", "held back, code step not held"]
    }

    /** No try is spent on it: nothing went wrong in code the release does not hold. */
    def "a code step the release does not hold is held rather than started, whoever it is asked of"() {
        given:
        def code = unstarted(codeStep(1, 2, null, producer))

        expect:
        EngineActs.next(RunInTree.root(run([code])), [position(at)], DEEP_ENOUGH) == new EngineAct.HoldNotHeld(Runs.RUN, code.planned(), null)

        where:
        [at, producer] << [["not started", "running its next try"], [Runs.code(), person()]].combinations()
    }

    def "a stop in force holds a code step's next try ahead of the release not holding it"() {
        given:
        def code = unstarted(codeStep(1, 2, released ? Runs.tidy(false) : null), STOP)

        expect:
        EngineActs.next(RunInTree.root(run([code])), [position("running its next try")], DEEP_ENOUGH) == new EngineAct.HoldOnStop(Runs.RUN, code.planned())

        where:
        released << [true, false]
    }

    /** The release is read afresh each time, so a release holding it again lets it go on by itself. */
    def "a code step held for the release not holding it is released once the release holds it, and left until then"() {
        given:
        def code = started(codeStep(1, 2, released ? Runs.tidy(false) : null), [],
                hold(RunStepHoldReason.CODE_STEP_NOT_HELD, minutes(40)))

        expect:
        EngineActs.next(RunInTree.root(run([code])), [position("held back, code step not held")], DEEP_ENOUGH) ==
                (released ? new EngineAct.ReleaseHold(Runs.RUN, code.planned()) : new EngineAct.Nothing(Runs.RUN))

        where:
        released << [true, false]
    }

    def "a code step a person produces, not yet started, is asked of its first try as a person's question is"() {
        given:
        def code = unstarted(codeStep(1, 2, Runs.tidy(false), person()))

        expect:
        EngineActs.next(RunInTree.root(run([code])), [position("not started")], DEEP_ENOUGH) == new EngineAct.StartTry(Runs.RUN, code.planned(), 1)
    }

    def "a person's question not yet started, with no stop on it, is asked of its first try"() {
        given:
        def steps = (0..<before).collect { unstarted(question(it + 1, person(), 1)) } +
                [step("a person's question", before + 1, null)]
        def positions = (0..<before).collect { position("done") } + [position("not started")]

        expect:
        EngineActs.next(RunInTree.root(run(steps)), positions, DEEP_ENOUGH) == new EngineAct.StartTry(Runs.RUN, steps.last().planned(), 1)

        where:
        before << [0, 2]
    }

    def "a person's question whose last try the model reviewing it refused is asked of its next try"() {
        given:
        def answer = value(1, "answer", true)
        def refusedOnce = started(question(1, person(), 3, MODEL), [yielded(1, StepProducer.PERSON, [answer],
                [modelReview(minutes(30), [refused(answer)])])])

        expect:
        EngineActs.next(RunInTree.root(run([refusedOnce])), [position("running its next try")], DEEP_ENOUGH) ==
                new EngineAct.StartTry(Runs.RUN, refusedOnce.planned(), 2)
    }

    def "a model's question reached with no try, or due its next try, is asked of that try by the system"() {
        given:
        def steps = (0..<before).collect { unstarted(question(it + 1, person(), 1)) } +
                [started(question(before + 1, model(), 3), (1..<number).collect { lost(it, StepProducer.MODEL) })]
        def positions = (0..<before).collect { position("done") } + [position(at)]

        expect:
        EngineActs.next(RunInTree.root(run(steps)), positions, DEEP_ENOUGH) == new EngineAct.StartTry(Runs.RUN, steps.last().planned(), number)

        where:
        [at, number, before] << [["not started", "running its next try"], [1, 2], [0, 1]].combinations()
    }

    def "a model's open try with nothing sent for it is sent, and no try is asked in its place"() {
        given:
        def asked = started(question(1, model(), 3), (1..<number).collect { lost(it, StepProducer.MODEL) } +
                [open(number, StepProducer.MODEL)])

        expect:
        EngineActs.next(RunInTree.root(run([asked])), [position("running its next try")], DEEP_ENOUGH) == new EngineAct.Send(Runs.RUN, asked.planned(), asked.newest().orElseThrow().id())

        where:
        number << [1, 2]
    }

    /** A stop holds what the step would do next, whether that is asking a try or sending one already asked. */
    def "a model's open try with nothing sent is held on a stop in force rather than sent"() {
        given:
        def asked = started(question(1, model(), 2), [open(1, StepProducer.MODEL)], null, [],
                stop == "the entry's" ? STOP : null)

        expect:
        EngineActs.next(RunInTree.root(run([asked], false, stop == "the workflow's" ? STOP : null)), [position("running its next try")], DEEP_ENOUGH) ==
                new EngineAct.HoldOnStop(Runs.RUN, asked.planned())

        where:
        stop << ["the workflow's", "the entry's"]
    }

    def "a step running a call whose try was sent already, or is not a model's, is left to the call"() {
        given:
        def asked = started(question(1, producer == StepProducer.MODEL ? model() : person(), 2), [newest])

        expect:
        EngineActs.next(RunInTree.root(run([asked])), [position("running a call")], DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)

        where:
        producer            | newest
        StepProducer.MODEL  | sent(open(1, StepProducer.MODEL), true)
        StepProducer.MODEL  | sent(open(1, StepProducer.MODEL), false)
        StepProducer.PERSON | open(1, StepProducer.PERSON)
    }

    def "code a model would produce is left to its producer"() {
        expect:
        EngineActs.next(RunInTree.root(run([step("code a model produces", 1, null)])), [position(at)], DEEP_ENOUGH) ==
                new EngineAct.Nothing(Runs.RUN)

        where:
        at << ["not started", "running its next try"]
    }

    def "a reached workflow starts its pinned version beneath its own run"() {
        given:
        def workflow = unstarted(workflowStep(1))

        expect:
        EngineActs.next(RunInTree.root(run([workflow])), DEEP_ENOUGH) ==
                new EngineAct.StartBeneath(Runs.RUN, workflow.planned(), workflow.planned().runs().pinned().version(), null)
    }

    def "a first step not done that a person, a review, something out, or a failure owns leaves the run to it"() {
        given:
        def steps = [step("a person's question", 1, null), step("a person's question", 2, null)]

        expect:
        EngineActs.next(RunInTree.root(run(steps)), [position(at), position("not started")], DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)

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
        EngineActs.next(RunInTree.root(run([produced, second], false, stop == "the workflow's" ? STOP : null)),
                [position("awaiting the model's review"), position("not started")], DEEP_ENOUGH) ==
                new EngineAct.Review(Runs.RUN, produced.planned(), produced.newest().orElseThrow().id())

        where:
        stop << ["none", "the entry's", "the workflow's"]
    }

    def "a code step's unsent review is planned even when the running release no longer holds the code step"() {
        given:
        def code = started(new PlannedStep(stepId(1), 1, new StepId("step_1"), null,
                new StepRuns.Code("tidy", held ? Runs.tidy(true) : null), Runs.code(), 2, MODEL, []),
                [yielded(1, StepProducer.CODE, [value(1, "answer", true)])])

        expect:
        EngineActs.next(RunInTree.root(run([code])), [position("awaiting the model's review")], DEEP_ENOUGH) ==
                new EngineAct.Review(Runs.RUN, code.planned(), code.newest().orElseThrow().id())

        where:
        held << [true, false]
    }

    def "a run whose every step is done has nothing left to do"() {
        given:
        def steps = [step("a person's question", 1, null), step("code", 2, null)]

        expect:
        EngineActs.next(RunInTree.root(run(steps)), [position("done"), position("done")], DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)
    }

    def "what a run does next cannot be worked out from other than one position per step"() {
        when:
        EngineActs.next(RunInTree.root(run([step("a person's question", 1, null)])), [], DEEP_ENOUGH)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "EngineActs was handed other than one position per step"
    }

    def "an unfinished child decides the next act in its own run despite its parent's stopped workflow"() {
        given:
        def childId = new RunId(Runs.key(999))
        def childStep = unstarted(question(1, model(), 1))
        def child = new RunSnapshot(childId, Runs.RUN, Runs.GROUP, Runs.VERSION, false, null, null,
                Runs.workflowOf([childStep.planned()]), [childStep])
        def parent = unstarted(workflowStep(1))
        def parentWithChild = new StepSnapshot(parent.planned(), null, null, null, [], [],
                new StepSnapshot.Beneath(child, null), [:])

        when:
        def act = EngineActs.next(RunInTree.root(run([parentWithChild], false, STOP)), DEEP_ENOUGH)

        then:
        act == new EngineAct.StartTry(childId, childStep.planned(), 1)
        act.run() != Runs.RUN
    }

    def "a nested child keeps the deepest run's action and step identity"() {
        given:
        def deepestId = new RunId(Runs.key(998))
        def middleId = new RunId(Runs.key(997))
        def deepestStep = unstarted(question(1, model(), 1))
        def deepest = new RunSnapshot(deepestId, Runs.RUN, Runs.GROUP, Runs.VERSION, false, null, null,
                Runs.workflowOf([deepestStep.planned()]), [deepestStep])
        def middleStep = unstarted(workflowStep(1))
        def middleWithChild = new StepSnapshot(middleStep.planned(), null, null, null, [], [],
                new StepSnapshot.Beneath(deepest, null), [:])
        def middle = new RunSnapshot(middleId, Runs.RUN, Runs.GROUP, Runs.VERSION, false, null, null,
                Runs.workflowOf([middleStep.planned()]), [middleWithChild])
        def rootStep = unstarted(workflowStep(1))
        def rootWithChild = new StepSnapshot(rootStep.planned(), null, null, null, [], [],
                new StepSnapshot.Beneath(middle, null), [:])

        when:
        def act = EngineActs.next(RunInTree.root(run([rootWithChild])), DEEP_ENOUGH)

        then:
        act == new EngineAct.StartTry(deepestId, deepestStep.planned(), 1)
        act.run() != middleId
        act.run() != Runs.RUN
    }

    def "a reached workflow stopped at its pinned entry is held and starts when let go"() {
        given:
        def planned = workflowStep(1)
        def stopped = unstarted(planned, STOP)
        def heldStep = started(planned, [], hold(RunStepHoldReason.ENTRY_STOPPED, minutes(40)))

        expect:
        EngineActs.next(RunInTree.root(run([stopped])), DEEP_ENOUGH) == new EngineAct.HoldOnStop(Runs.RUN, planned)
        EngineActs.next(RunInTree.root(run([heldStep])), DEEP_ENOUGH) == new EngineAct.ReleaseHold(Runs.RUN, planned)
    }

    def "a route starts the matching case or its fallback at the pinned version"() {
        given:
        def scenario = routeScenario(said, true)
        def chosen = fallback ? scenario.fallback : scenario.match

        when:
        def act = EngineActs.next(RunInTree.root(scenario.run), DEEP_ENOUGH)

        then:
        act == new EngineAct.StartBeneath(Runs.RUN, scenario.route.planned(),
                chosen.target().version(), chosen.id())
        act.routeCase() != (fallback ? scenario.match.id() : scenario.fallback.id())

        where:
        said      | fallback
        "match"   | false
        "unknown" | true
        null      | true
    }

    def "an unclaimed standing route value fails without a try or child"() {
        given:
        def scenario = routeScenario(said, false)

        when:
        def act = EngineActs.next(RunInTree.root(scenario.run), DEEP_ENOUGH)

        then:
        act == new EngineAct.FailUnclaimed(Runs.RUN, scenario.route.planned(), said)
        scenario.route.tries().isEmpty()
        scenario.route.beneath() == null

        where:
        said << ["unknown", null]
    }

    /** What no letting go could mend is said first: holding it would only fail it once the stop was let go. */
    def "an unclaimed standing route value fails rather than being held, whatever stop is in force and however deep"() {
        given:
        def scenario = routeScenario(said, false, stoppedCase)
        def stoppedRun = run(scenario.run.steps(), false, workflowStopped ? STOP : null)

        when:
        def act = EngineActs.next(RunInTree.root(stoppedRun), most)

        then:
        act == new EngineAct.FailUnclaimed(Runs.RUN, scenario.route.planned(), said)

        where:
        [said, stoppedCase, workflowStopped, most] << [["unknown", null], ["match", null], [true, false], [1, 32]]
                .combinations()
    }

    /**
     * Ruled: what no letting go could mend comes first. A value no case claims fails before a run too deep is
     * judged, and a run too deep fails before any stop holds the step; only a step that could start is held.
     */
    def "a route fails on a value no case claims, then on going too deep, and only then is held by a stop"() {
        given:
        def scenario = routeScenario(said, fallback, stoppedCase)
        def at = RunInTree.root(run(scenario.run.steps(), false, workflowStopped ? STOP : null))

        when:
        def act = EngineActs.next(at, most)

        then:
        act.getClass() == expected
        act.run() == Runs.RUN
        act.step() == scenario.route.planned()

        where:
        said      | fallback | stoppedCase | workflowStopped | most || expected
        "unknown" | false    | null        | true            | 1    || EngineAct.FailUnclaimed
        "unknown" | false    | "match"     | false           | 1    || EngineAct.FailUnclaimed
        "unknown" | false    | "match"     | true            | 32   || EngineAct.FailUnclaimed
        "match"   | false    | "match"     | false           | 1    || EngineAct.FailTooDeep
        "match"   | false    | null        | true            | 1    || EngineAct.FailTooDeep
        "match"   | false    | null        | false           | 1    || EngineAct.FailTooDeep
        "unknown" | true     | null        | false           | 1    || EngineAct.FailTooDeep
        "match"   | false    | "match"     | false           | 32   || EngineAct.HoldOnStop
        "match"   | false    | null        | true            | 32   || EngineAct.HoldOnStop
        "match"   | false    | null        | false           | 32   || EngineAct.StartBeneath
    }

    /** As for a route: a run too deep fails before a stop on the workflow or on what the step runs holds it. */
    def "a workflow step fails on going too deep before any stop holds it, and starts its run where neither applies"() {
        given:
        def planned = workflowStep(1)
        def at = RunInTree.root(run([unstarted(planned, pinStopped ? STOP : null)], false, workflowStopped ? STOP : null))

        when:
        def act = EngineActs.next(at, most)

        then:
        act.getClass() == expected
        act.step() == planned

        where:
        pinStopped | workflowStopped | most || expected
        true       | false           | 1    || EngineAct.FailTooDeep
        false      | true            | 1    || EngineAct.FailTooDeep
        false      | false           | 1    || EngineAct.FailTooDeep
        true       | false           | 2    || EngineAct.HoldOnStop
        false      | true            | 2    || EngineAct.HoldOnStop
        false      | false           | 2    || EngineAct.StartBeneath
    }

    /** Nothing is known of the branch it would take, so nothing says it would start a run too deep. */
    def "a route whose value does not yet stand is held on its workflow's stop, never failed as too deep"() {
        given:
        def scenario = routeScenario("match", true)
        def waitingSource = unstarted(scenario.run.steps().first().planned())
        def at = RunInTree.root(run([waitingSource, scenario.route], false, STOP))

        when:
        def act = EngineActs.next(at, [position("done"), position("running its next try")], 1)

        then:
        act == new EngineAct.HoldOnStop(Runs.RUN, scenario.route.planned())
    }

    def "how deep runs may go is refused below one, whatever the run"() {
        when:
        EngineActs.next(RunInTree.root(run([unstarted(workflowStep(1))])), most)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "EngineActs maximumDepth must be positive: " + most

        where:
        most << [0, -1]
    }

    def "a route holds only for its selected target, and releases when that target is let go"() {
        given:
        def stopped = routeScenario("match", true, "match", false)
        def unrelated = routeScenario("match", true, "fallback", false)
        def stillHeld = routeScenario("match", true, "match", true)
        def released = routeScenario("match", true, null, true)

        expect:
        EngineActs.next(RunInTree.root(stopped.run), DEEP_ENOUGH) ==
                new EngineAct.HoldOnStop(Runs.RUN, stopped.route.planned())
        EngineActs.next(RunInTree.root(unrelated.run), DEEP_ENOUGH) ==
                new EngineAct.StartBeneath(Runs.RUN, unrelated.route.planned(),
                        unrelated.match.target().version(), unrelated.match.id())
        EngineActs.next(RunInTree.root(stillHeld.run), DEEP_ENOUGH) == new EngineAct.Nothing(Runs.RUN)
        EngineActs.next(RunInTree.root(released.run), DEEP_ENOUGH) ==
                new EngineAct.ReleaseHold(Runs.RUN, released.route.planned())
    }

    def "a route waits if its discriminator has not come to stand"() {
        given:
        def scenario = routeScenario("match", true)
        def waitingSource = unstarted(scenario.run.steps().first().planned())
        def waitingRun = run([waitingSource, scenario.route])

        expect:
        EngineActs.next(RunInTree.root(waitingRun), [position("done"), position("running its next try")], DEEP_ENOUGH) ==
                new EngineAct.Nothing(Runs.RUN)
    }

    private static Map routeScenario(String said, boolean hasFallback, String stoppedCase = null,
                                     boolean isHeld = false) {
        def source = started(question(1, person(), 1),
                [yielded(1, StepProducer.PERSON, [value(1, "answer", false, said)])])
        def discriminator = new Binding(Runs.key(801), null,
                new BindingSource.StepOutput(source.planned().id().value(), Pointer.parse("answer")))
        def match = new StepRuns.Route.Case(Runs.key(802), "match", Runs.pinned(2), [])
        def fallback = new StepRuns.Route.Case(Runs.key(803), null, Runs.pinned(3), [])
        def template = routeStep(2)
        def cases = hasFallback ? [match, fallback] : [match]
        def planned = new PlannedStep(template.id(), template.order(), template.name(), template.label(),
                new StepRuns.Route(discriminator, template.runs().gives(), cases), null, null, null, [])
        def stopped = stoppedCase == "match" ? [(match.id()): STOP] :
                stoppedCase == "fallback" ? [(fallback.id()): STOP] : [:]
        def route = new StepSnapshot(planned, null, isHeld ? new RunStepId(Runs.key(804)) : null,
                isHeld ? hold(RunStepHoldReason.ENTRY_STOPPED, minutes(40)) : null,
                [], [], null, stopped)
        [run: run([source, route]), route: route, match: match, fallback: fallback]
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
        new PlannedStep(stepId(order), order, new StepId("step_" + order), null, new StepRuns.Code("tidy", Runs.tidy(false)),
                producer, 2, null, [])
    }

    /** {@code open} with an attempt to produce it made, and its call written down where the attempt was not too long. */
    private static TryRecord sent(TryRecord open, boolean called) {
        new TryRecord(open.id(), open.number(), open.producer(), null, open.askedAt(), null, null, null, null, null,
                false, null, false, false, null, [], [], [],
                [new AttemptRecord(attemptId(1), ModelCallPurpose.PRODUCE, !called, null)],
                called ? [new CallRecord(new ModelCallId(Runs.key(1100)), attemptId(1), null, false)] : [])
    }
}
