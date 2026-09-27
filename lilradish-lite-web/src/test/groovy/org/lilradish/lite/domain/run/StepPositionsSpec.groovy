package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.RunStepHoldReason.ENTRY_STOPPED
import static org.lilradish.lite.domain.run.fixture.Runs.MODEL
import static org.lilradish.lite.domain.run.fixture.Runs.PERSON
import static org.lilradish.lite.domain.run.fixture.Runs.after
import static org.lilradish.lite.domain.run.fixture.Runs.askedAt
import static org.lilradish.lite.domain.run.fixture.Runs.assured
import static org.lilradish.lite.domain.run.fixture.Runs.codeStep
import static org.lilradish.lite.domain.run.fixture.Runs.endedAt
import static org.lilradish.lite.domain.run.fixture.Runs.hold
import static org.lilradish.lite.domain.run.fixture.Runs.lengthReview
import static org.lilradish.lite.domain.run.fixture.Runs.lost
import static org.lilradish.lite.domain.run.fixture.Runs.lostReview
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.model
import static org.lilradish.lite.domain.run.fixture.Runs.modelReview
import static org.lilradish.lite.domain.run.fixture.Runs.open
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.personReview
import static org.lilradish.lite.domain.run.fixture.Runs.position
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.routeStep
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.started
import static org.lilradish.lite.domain.run.fixture.Runs.stop
import static org.lilradish.lite.domain.run.fixture.Runs.tryId
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.valueId
import static org.lilradish.lite.domain.run.fixture.Runs.workflowStep
import static org.lilradish.lite.domain.run.fixture.Runs.yielded
import static org.lilradish.lite.domain.workflow.StepProducer.CODE
import static org.lilradish.lite.domain.workflow.StepProducer.MODEL as BY_MODEL
import static org.lilradish.lite.domain.workflow.StepProducer.PERSON as BY_PERSON

import java.time.Instant
import org.lilradish.lite.domain.inference.ModelCallOutcome
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.run.fixture.Runs
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.StepId
import spock.lang.Specification

class StepPositionsSpec extends Specification {

    static final Instant STOP = minutes(100)

    static final Instant REVIEWED = minutes(200)

    def "where a run's steps are is read one per step, in the order they run, each with the stop in force"() {
        given:
        def finished = value(1, "answer", false)
        def running = run([
                started(question(1, person(), 1), [yielded(1, BY_PERSON, [finished], [], PERSON)]),
                started(question(2, person(), 2), [open(1, BY_PERSON)]),
                unstarted(question(3, model(), 1)),
        ], false, stop(STOP))

        expect:
        StepPositions.of(running) == [
                new StepPosition.Done(),
                new StepPosition.HeldBack(ENTRY_STOPPED, STOP, new StepPosition.Owed(1, true, false, askedAt(1))),
                new StepPosition.NotStarted(),
        ]
    }

    /** The workflow's stop holds whatever the step runs, so where both are in force it is the one read. */
    def "a try only a person may make reads as held back from when the stop holding it began, holding the try it owes"() {
        given:
        def pinned = stopped == "the workflow's" ? null : stop(STOP)
        def workflow = stopped == "the entry's" ? null : stop(stopped == "the workflow's" ? STOP : minutes(120))
        def step = owing(owed, pinned)
        def unstopped = owing(owed, null)
        def owedUnstopped = StepPositions.of(run([unstopped]), unstopped)

        when:
        def position = StepPositions.of(run([step], false, workflow), step)

        then:
        position == new StepPosition.HeldBack(ENTRY_STOPPED, since, owedUnstopped as StepPosition.Owed)
        owedUnstopped instanceof StepPosition.Owed

        where:
        [owed, stopped] << [
                ["asked of a person", "owed after code was lost", "owed after a person refused"],
                ["the workflow's", "the entry's", "the entry's, then the workflow's"],
        ].combinations()
        since = stopped == "the entry's, then the workflow's" ? minutes(120) : STOP
    }

    /** Values already produced may still be reviewed while what the step runs is stopped. */
    def "a step owing no person's try reads as it would unstopped while a stop is in force"() {
        given:
        def step = owing(key, null)

        expect:
        StepPositions.of(run([step], false, stop(STOP)), step) == expected
        StepPositions.of(run([step]), step) == expected

        where:
        key                         || expected
        "values waiting on review"  || new StepPosition.AwaitingReview(1,
                [new StepPosition.WaitingValue(valueId(1), "answer", WaitsOn.REVIEW_AT_GATE)], PERSON, endedAt(1), null)
        "failed with its tries spent" || new StepPosition.Failed(new StepFailure.TriesSpent(1, 1), endedAt(1))
        "done"                      || new StepPosition.Done()
        "not started"               || new StepPosition.NotStarted()
        "running a call"            || new StepPosition.Running(RunningOn.CALL)
    }

    /**
     * A code step or a model's reached reads as the try the system makes by itself, until its try is written; one a
     * person makes, whether of a question or a code step, is asked of them and reads as not started until then.
     */
    def "a step with no try has not started, save a code or a model's step reached, which reads as its try being made"() {
        expect:
        StepPositions.unheld(unstarted(planned(runs, 1)), reached) == expected
        StepPositions.unheld(started(planned(runs, 1), []), reached) == expected

        where:
        runs                | reached || expected
        "code"              | true    || new StepPosition.Running(RunningOn.NEXT_TRY)
        "code"              | false   || new StepPosition.NotStarted()
        "code by a person"  | true    || new StepPosition.NotStarted()
        "person"   | true    || new StepPosition.NotStarted()
        "person"   | false   || new StepPosition.NotStarted()
        "model"    | true    || new StepPosition.Running(RunningOn.NEXT_TRY)
        "model"    | false   || new StepPosition.NotStarted()
        "workflow" | true    || new StepPosition.NotStarted()
        "route"    | true    || new StepPosition.NotStarted()
    }

    def "a model's step is reached once every step before it is done, and read so alone or with the rest"() {
        given:
        def first = before == "done" ? started(question(1, person(), 1), [yielded(1, BY_PERSON,
                [value(1, "answer", false)], [], PERSON)]) : started(question(1, person(), 1), [open(1, BY_PERSON)])
        def asked = unstarted(question(2, model(), 1))
        def running = run([first, asked])

        expect:
        StepPositions.of(running)[1] == expected
        StepPositions.of(running, asked) == expected

        where:
        before  || expected
        "done"  || new StepPosition.Running(RunningOn.NEXT_TRY)
        "asked" || new StepPosition.NotStarted()
    }

    def "a code step is reached once every step before it is done, and read so alone or with the rest"() {
        given:
        def first = before == "done" ? started(question(1, person(), 1), [yielded(1, BY_PERSON,
                [value(1, "answer", false)], [], PERSON)]) : started(question(1, person(), 1), [open(1, BY_PERSON)])
        def code = unstarted(codeStep(2, 1))
        def running = run([first, code])

        expect:
        StepPositions.of(running)[1] == expected
        StepPositions.of(running, code) == expected

        where:
        before  || expected
        "done"  || new StepPosition.Running(RunningOn.NEXT_TRY)
        "asked" || new StepPosition.NotStarted()
    }

    def "a step its run does not hold is refused rather than read"() {
        when:
        StepPositions.of(run([unstarted(question(1, person(), 1))]), unstarted(question(2, person(), 1)))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepPositions was asked of a step its run does not hold"
    }

    def "a hold not yet released reads as held back for its reason from when it began, whatever tries the step holds"() {
        given:
        def step = started(question(1, person(), 2), tries == "none" ? [] : [open(1, BY_PERSON)],
                hold(reason, minutes(40)), [new FailureRecord(failureId(1), RunStepFailureReason.UNCLAIMED_VALUE, null,
                null, minutes(30), false)])

        expect:
        StepPositions.of(run([step], false, stopped ? stop(STOP) : null), step) ==
                new StepPosition.HeldBack(reason, minutes(40), null)

        where:
        [reason, tries, stopped] << [RunStepHoldReason.values().toList(), ["none", "an open one"], [true, false]]
                .combinations()
                .findAll { reason, tries, stopped -> stopped || reason != ENTRY_STOPPED }
    }

    /** Nothing went on after the stop was let go, so the hold is left until acting on the step releases it. */
    def "a hold on a stop let go reads as the try a person owes on a person's question, and as held back otherwise"() {
        given:
        def step = started(planned, [], hold(ENTRY_STOPPED, minutes(40)))

        expect:
        StepPositions.of(run([step]), step) == expected

        where:
        planned                                        || expected
        question(1, person(), 2)                       || new StepPosition.Owed(1, true, false, minutes(40))
        codeStep(1, 2, Runs.tidy(false), person())     || new StepPosition.Owed(1, true, false, minutes(40))
        codeStep(1, 2, null, person())                 || new StepPosition.HeldBack(RunStepHoldReason.CODE_STEP_NOT_HELD, minutes(40), null)
        question(1, model(), 2)                        || new StepPosition.HeldBack(ENTRY_STOPPED, minutes(40), null)
        codeStep(1, 2)                                 || new StepPosition.HeldBack(ENTRY_STOPPED, minutes(40), null)
        workflowStep(1)                                || new StepPosition.HeldBack(ENTRY_STOPPED, minutes(40), null)
    }

    def "a failure written down and not answered, on the newest try or on none, reads as failed from when it was written"() {
        given:
        def tries = placed == "on no try, before any" ? [] :
                [placed == "on the newest try, which gave back what stands"
                         ? yielded(1, BY_PERSON, [value(1, "answer", false)], [], PERSON)
                         : open(1, BY_PERSON)]
        def onTry = placed.startsWith("on the newest try") ? tryId(1) : null
        def step = started(question(1, person(), 2), tries, null,
                [new FailureRecord(failureId(1), reason, onTry, onTry == null ? null : ModelCallPurpose.PRODUCE,
                        minutes(50), false)])

        expect:
        StepPositions.unheld(step, true) == new StepPosition.Failed(
                new StepFailure.Recorded(reason, onTry == null ? null : ModelCallPurpose.PRODUCE), minutes(50))

        where:
        [reason, placed] << [
                RunStepFailureReason.values().toList(),
                ["on no try, before any", "on no try, beside an open one", "on the newest try",
                 "on the newest try, which gave back what stands"],
        ].combinations()
    }

    def "of the failures written the last is read, and an answer to it ends every one before it"() {
        given:
        def failures = [
                new FailureRecord(failureId(1), RunStepFailureReason.UNCUTTABLE_LENGTH, null, null, minutes(50), false),
                new FailureRecord(failureId(2), RunStepFailureReason.MODEL_NOT_DEPLOYED, null, null, minutes(60),
                        laterAnswered),
        ]
        def step = started(question(1, person(), 2), [open(1, BY_PERSON)], null, failures)

        expect:
        StepPositions.unheld(step, true) == read

        where:
        laterAnswered || read
        false         || new StepPosition.Failed(new StepFailure.Recorded(RunStepFailureReason.MODEL_NOT_DEPLOYED, null),
                minutes(60))
        true          || new StepPosition.Owed(1, true, false, askedAt(1))
    }

    def "a failure answered, or on a try before the newest, has ended and leaves the newest try to decide"() {
        given:
        def failure = new FailureRecord(failureId(1), RunStepFailureReason.UNCLAIMED_VALUE,
                onTry == null ? null : tryId(onTry), onTry == null ? null : ModelCallPurpose.PRODUCE, minutes(50), answered)
        def step = started(question(1, person(), 2), after(open(2, BY_PERSON)), null, [failure])

        expect:
        StepPositions.unheld(step, true) == new StepPosition.Owed(2, true, false, askedAt(2))

        where:
        onTry | answered
        2     | true
        1     | false
        1     | true
        null  | true
    }

    def "a step the run holds a row of but no try has not started"() {
        given:
        def failures = answeredFailure
                ? [new FailureRecord(failureId(1), RunStepFailureReason.UNCLAIMED_VALUE, null, null, minutes(50), true)] : []
        def step = started(question(1, person(), 1), [], null, failures)

        expect:
        StepPositions.unheld(step, true) == new StepPosition.NotStarted()

        where:
        answeredFailure << [false, true]
    }

    /** The one a failed step reads as, whole, what it was sending for included. */
    def "the failure in force is the last written on the newest try or on none, and none once it is answered"() {
        given:
        def onEarlier = new FailureRecord(failureId(1), RunStepFailureReason.MODEL_NOT_DEPLOYED, tryId(1),
                ModelCallPurpose.PRODUCE, minutes(40), false)
        def onNewest = new FailureRecord(failureId(2), RunStepFailureReason.MODEL_NOT_DEPLOYED, tryId(2),
                ModelCallPurpose.REVIEW, minutes(50), answered)
        def step = started(question(1, model(), 3, MODEL), after(yielded(2, BY_MODEL, [value(1, "answer", true)])),
                null, [onEarlier, onNewest])

        expect:
        StepPositions.inForce(step) == (answered ? Optional.empty() : Optional.of(onNewest))

        where:
        answered << [false, true]
    }

    /** A model's try reads as its call out only once an attempt to produce it is written; until then it is being made. */
    def "an open newest try is owed where a person makes it, beyond the declared tries past them, and otherwise running"() {
        given:
        def newest = sent ? attempted(open(number, producer)) : open(number, producer)
        def step = started(planned(named, declared), after(newest))

        expect:
        StepPositions.unheld(step, true) == expected

        where:
        named    | producer  | number | declared | sent  || expected
        "person" | BY_PERSON | 1      | 2        | false || new StepPosition.Owed(1, true, false, askedAt(1))
        "person" | BY_PERSON | 2      | 2        | false || new StepPosition.Owed(2, true, false, askedAt(2))
        "person" | BY_PERSON | 3      | 2        | false || new StepPosition.Owed(3, true, true, askedAt(3))
        "model"  | BY_PERSON | 1      | 1        | false || new StepPosition.Owed(1, true, false, askedAt(1))
        "code"   | CODE      | 1      | 1        | false || new StepPosition.Running(RunningOn.CODE)
        "model"  | BY_MODEL  | 1      | 1        | true  || new StepPosition.Running(RunningOn.CALL)
        "model"  | BY_MODEL  | 2      | 1        | true  || new StepPosition.Running(RunningOn.CALL)
        "model"  | BY_MODEL  | 1      | 1        | false || new StepPosition.Running(RunningOn.NEXT_TRY)
        "model"  | BY_MODEL  | 2      | 1        | false || new StepPosition.Running(RunningOn.NEXT_TRY)
    }

    private static TryRecord attempted(TryRecord open) {
        new TryRecord(open.id(), open.number(), open.producer(), null, open.askedAt(), null, null, null, null, null,
                null, false, null, [], [], [],
                [new AttemptRecord(Runs.attemptId(1), ModelCallPurpose.PRODUCE, false, null)], [])
    }

    def "a lost newest try spends the step once it is the last declared, and is otherwise followed by the next"() {
        given:
        def step = started(planned(named, declared), after(lost(number, producer)))

        expect:
        StepPositions.unheld(step, true) == expected

        where:
        named                     | producer | number | declared || expected
        "model"                   | BY_MODEL | 1      | 2        || new StepPosition.Running(RunningOn.NEXT_TRY)
        "code"                    | CODE     | 1      | 2        || new StepPosition.Owed(2, false, false, endedAt(1))
        "code"                    | CODE     | 2      | 3        || new StepPosition.Owed(3, false, false, endedAt(2))
        "code that may run again" | CODE     | 1      | 2        || new StepPosition.Running(RunningOn.NEXT_TRY)
        "code that may run again" | CODE     | 2      | 3        || new StepPosition.Running(RunningOn.NEXT_TRY)
        "model"                   | BY_MODEL | 2      | 2        || new StepPosition.Failed(new StepFailure.TriesSpent(2, 2), endedAt(2))
        "code"                    | CODE     | 1      | 1        || new StepPosition.Failed(new StepFailure.TriesSpent(1, 1), endedAt(1))
        "code"                    | CODE     | 3      | 2        || new StepPosition.Failed(new StepFailure.TriesSpent(3, 2), endedAt(3))
        "code that may run again" | CODE     | 2      | 2        || new StepPosition.Failed(new StepFailure.TriesSpent(2, 2), endedAt(2))
    }

    /** What the release says now decides, never what it said when the try was made. */
    def "a code step's next try is made by itself or owed as the release says of its code now, and held where it holds none"() {
        given:
        def step = started(codeStep(1, 3, released), [lost(1, CODE)])

        expect:
        StepPositions.of(run([step]), step) == expected

        where:
        released         || expected
        Runs.tidy(true)  || new StepPosition.Running(RunningOn.NEXT_TRY)
        Runs.tidy(false) || new StepPosition.Owed(2, false, false, endedAt(1))
        null             || new StepPosition.HeldBack(RunStepHoldReason.CODE_STEP_NOT_HELD, endedAt(1), null)
    }

    /** Nobody may answer what no release declares, so every try a person might make of it is held instead. */
    def "a try a person may make of a code step the release does not hold is held back, and one being made is left to the engine"() {
        given:
        def step = started(codeStep(1, declared, null, producer), tries)

        expect:
        StepPositions.of(run([step]), step) == expected

        where:
        producer     | declared | tries                        || expected
        Runs.code()  | 1        | [lost(1, CODE)]              || new StepPosition.HeldBack(RunStepHoldReason.CODE_STEP_NOT_HELD, endedAt(1), null)
        person()     | 2        | [open(1, BY_PERSON)]         || new StepPosition.HeldBack(RunStepHoldReason.CODE_STEP_NOT_HELD, askedAt(1), null)
        Runs.code()  | 2        | []                           || new StepPosition.Running(RunningOn.NEXT_TRY)
        Runs.code()  | 2        | [open(1, CODE)]              || new StepPosition.Running(RunningOn.CODE)
    }

    def "a code step the release does not hold is held back for that rather than on a stop, so no answer is offered"() {
        given:
        def step = started(codeStep(1, 3, null), [lost(1, CODE)], null, [], stop(STOP))

        expect:
        StepPositions.of(run([step]), step) ==
                new StepPosition.HeldBack(RunStepHoldReason.CODE_STEP_NOT_HELD, endedAt(1), null)
    }

    /** Code the release lets run only once is never run again by itself, whoever refused what it gave. */
    def "a code step's values the model refused are tried again by the code where it may run again, and owed a person where not"() {
        given:
        def produced = value(1, "answer", true)
        def step = started(new PlannedStep(Runs.stepId(1), 1, new StepId("step_1"),
                new StepRuns.Code("tidy", Runs.tidy(mayRunAgain)), Runs.code(), 2, MODEL, []),
                [yielded(1, CODE, [produced], [modelReview(REVIEWED, [refused(produced)])])])

        expect:
        StepPositions.unheld(step, true) == expected

        where:
        mayRunAgain || expected
        true        || new StepPosition.Running(RunningOn.NEXT_TRY)
        false       || new StepPosition.Owed(2, false, false, REVIEWED)
    }

    def "a person's try lost with tries left is refused as what the store never holds"() {
        given:
        def step = started(question(1, person(), 2), [lost(1, BY_PERSON)])

        when:
        StepPositions.unheld(step, true)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Try " + tryId(1).value() + " of a person was lost"
    }

    def "a newest try whose every value stands leaves the step done"() {
        given:
        def unreviewed = value(1, "first", false)
        def reviewed = value(2, "second", true)
        def values = standing == "nothing given back" ? [] : (standing == "one needing no review" ? [unreviewed] :
                (standing == "one needing no review beside one a person assured" ? [unreviewed, reviewed] : [reviewed]))
        def reviews = !values.contains(reviewed) ? [] : (standing == "the model assured it"
                ? [modelReview(REVIEWED, [assured(reviewed)])] : [personReview(REVIEWED, [assured(reviewed)])])
        def step = started(question(1, person(), 1), [yielded(1, BY_PERSON, values, reviews, PERSON)])

        expect:
        StepPositions.unheld(step, true) == new StepPosition.Done()

        where:
        standing << ["nothing given back", "one needing no review", "a person assured it", "the model assured it",
                     "one needing no review beside one a person assured"]
    }

    def "a refused newest try with tries left owes a person's next try where a person or its length refused it, and runs the next otherwise"() {
        given:
        def step = refusedStep(refusal, number, declared)

        expect:
        StepPositions.unheld(step, true) == expected

        where:
        refusal                          | number | declared || expected
        "a person refused on review"     | 1      | 2        || new StepPosition.Owed(2, false, false, REVIEWED)
        "a person refused on review"     | 2      | 3        || new StepPosition.Owed(3, false, false, REVIEWED)
        "refused for its length"         | 1      | 2        || new StepPosition.Owed(2, false, false, REVIEWED)
        "the model refused on review"    | 1      | 2        || new StepPosition.Running(RunningOn.NEXT_TRY)
        "the model's review went wrong"  | 1      | 2        || new StepPosition.Running(RunningOn.NEXT_TRY)
    }

    def "a refused newest try that is the last declared, or beyond it, spends the step from when it was refused"() {
        given:
        def step = refusedStep(refusal, number, declared)

        expect:
        StepPositions.unheld(step, true) == new StepPosition.Failed(new StepFailure.TriesSpent(number, declared), REVIEWED)

        where:
        refusal                         | number | declared
        "a person refused on review"    | 2      | 2
        "a person refused on review"    | 3      | 2
        "refused for its length"        | 1      | 1
        "the model refused on review"   | 1      | 1
        "the model's review went wrong" | 2      | 2
    }

    /** Every try here was ended by a person, so what decides who produced the values is the producer alone. */
    def "values waiting on a review are named in order with whom they wait on, beside whoever produced them only where a person did"() {
        given:
        def produced = yielded(2, producer, [value(1, "first", false), value(2, "second", true), value(3, "third", true)],
                [], PERSON)
        def step = started(planned(producer == CODE ? "code" : (producer == BY_PERSON ? "person" : "model"), 2,
                reviewer), after(produced))

        expect:
        StepPositions.unheld(step, true) == new StepPosition.AwaitingReview(2, [
                new StepPosition.WaitingValue(valueId(2), "second", on),
                new StepPosition.WaitingValue(valueId(3), "third", on),
        ], producedBy, endedAt(2), sending)

        where:
        producer  | reviewer || on                     | producedBy | sending
        BY_PERSON | null     || WaitsOn.REVIEW_AT_GATE | PERSON     | null
        BY_PERSON | MODEL    || WaitsOn.MODEL          | PERSON     | ReviewSending.UNSENT
        BY_MODEL  | null     || WaitsOn.REVIEW_AT_GATE | null       | null
        BY_MODEL  | MODEL    || WaitsOn.MODEL          | null       | ReviewSending.UNSENT
        CODE      | null     || WaitsOn.REVIEW_AT_GATE | null       | null
    }

    /**
     * Waiting, never held back, however the model's review went out: only what nothing is ever sent for, too long or
     * not built, is a person's.
     */
    def "values waiting on the model's review wait on a person in its place only where nothing is ever sent for them"() {
        given:
        def step = started(question(1, model(), 2, MODEL),
                [reviewing(sent, yielded(1, BY_MODEL, [value(1, "answer", true)]))])

        expect:
        StepPositions.unheld(step, true) == new StepPosition.AwaitingReview(1,
                [new StepPosition.WaitingValue(valueId(1), "answer", on)], null, endedAt(1), sending)

        where:
        sent                               || on                     | sending
        "nothing sent"                     || WaitsOn.MODEL          | ReviewSending.UNSENT
        "its call out"                     || WaitsOn.MODEL          | ReviewSending.OUT
        "its call turned away"             || WaitsOn.MODEL          | ReviewSending.TURNED_AWAY
        "too long to send"                 || WaitsOn.REVIEW_AT_GATE | ReviewSending.TOO_LONG
        "not built, a list not here"       || WaitsOn.REVIEW_AT_GATE | ReviewSending.LIST_NOT_HERE
        "not built, takes no longer"       || WaitsOn.REVIEW_AT_GATE | ReviewSending.TAKES_NO_LONGER_DECLARED
        "not built, gives no longer"       || WaitsOn.REVIEW_AT_GATE | ReviewSending.NO_LONGER_DECLARED
    }

    /** A review that came back, went wrong or never did is on record, so nothing of the try waits on one. */
    def "a model's review on record decides the values it was sent, however its call ended"() {
        given:
        def waiting = value(1, "answer", true)
        def review = assuring ? modelReview(REVIEWED, [assured(waiting)]) : lostReview(REVIEWED)
        def step = started(question(1, model(), 2, MODEL),
                [Runs.sentToReview(yielded(1, BY_MODEL, [waiting], [review]), false, outcome)])

        expect:
        StepPositions.unheld(step, true) == expected

        where:
        assuring | outcome                            || expected
        true     | ModelCallOutcome.CAME_BACK         || new StepPosition.Done()
        false    | ModelCallOutcome.NOTHING_CAME_BACK || new StepPosition.Running(RunningOn.NEXT_TRY)
        false    | ModelCallOutcome.ERRORED           || new StepPosition.Running(RunningOn.NEXT_TRY)
    }

    def "a value refused for its length outranks values still waiting on a review"() {
        given:
        def tooLong = value(1, "first", false)
        def step = started(question(1, model(), 2), [yielded(1, BY_MODEL, [tooLong, value(2, "second", true)],
                [lengthReview(REVIEWED, [refused(tooLong)])])])

        expect:
        StepPositions.unheld(step, true) == new StepPosition.Owed(2, false, false, REVIEWED)
    }

    def "a refusal on review, not one for length, is what the next try is owed from, whichever value comes first"() {
        given:
        def tooLong = value(1, "first", false)
        def wrong = value(2, "second", true)
        def reviews = [lengthReview(minutes(300), [refused(tooLong)]), personReview(REVIEWED, [refused(wrong)])]
        def step = started(question(1, person(), 2), [yielded(1, BY_PERSON, lengthFirst ? [tooLong, wrong] : [wrong, tooLong],
                reviews, PERSON)])

        expect:
        StepPositions.unheld(step, true) == new StepPosition.Owed(2, false, false, REVIEWED)

        where:
        lengthFirst << [true, false]
    }

    def "a stopped run waits on nobody, wherever its steps are"() {
        given:
        def running = run([owing("asked of a person", null)], true)

        expect:
        StepPositions.waitsOn(running, position(named)) == null

        where:
        named << ["held back on a stop", "owed", "awaiting a person's review", "awaiting the model's review, turned away",
                  "failed with its tries spent", "running code", "not started", "done"]
    }

    /**
     * A step held back on a stop waits on whoever started the run, and on whoever may answer it once let go; a review
     * the model would not take waits on whoever started the run, as a step held back does, though it is not held.
     */
    def "a step waits on whoever may act on it where it is, and a step held back or failed on whoever started the run"() {
        expect:
        StepPositions.waitsOn(run([owing("asked of a person", null)]), position(named)) == expected

        where:
        named                                      || expected
        "held back on a stop"                      || WaitsOn.STARTER
        "held back too long"                       || WaitsOn.STARTER
        "failed with its tries spent"              || WaitsOn.STARTER
        "failed as written down"                   || WaitsOn.STARTER
        "asked"                                    || WaitsOn.ANSWER_STEP
        "owed"                                     || WaitsOn.ANSWER_STEP
        "awaiting a person's review"               || WaitsOn.REVIEW_AT_GATE
        "awaiting the model's review"              || WaitsOn.MODEL
        "awaiting the model's review, turned away" || WaitsOn.STARTER
        "running code"                             || null
        "running its next try"                     || null
        "not started"                              || null
        "done"                                     || null
    }

    def "the try a person owes is read the same as held back by a stop as it is let go, so no answer is lost"() {
        given:
        def step = owing("asked of a person", null)
        def held = StepPositions.of(run([step], false, stop(STOP)), step)
        def letGo = StepPositions.of(run([step]), step)

        expect:
        StepPositions.waitsOn(run([step]), held) == WaitsOn.STARTER
        StepPositions.waitsOn(run([step]), letGo) == WaitsOn.ANSWER_STEP
        held.nextTry() == letGo.nextTry()
    }

    private static RunStepFailureId failureId(int number) {
        new RunStepFailureId(Runs.key(1100 + number))
    }

    private static PlannedStep planned(String named, int declared, ModelChoice reviewer = null) {
        switch (named) {
            case "person": return question(1, person(), declared, reviewer)
            case "model": return question(1, model(), declared, reviewer)
            case "code": return codeStep(1, declared)
            case "code that may run again": return codeStep(1, declared, Runs.tidy(true))
            case "code by a person": return codeStep(1, declared, Runs.tidy(false), person())
            case "workflow": return workflowStep(1)
            case "route": return routeStep(1)
            default: throw new IllegalArgumentException(named)
        }
    }

    private static StepSnapshot owing(String key, StopRecord pinStopped) {
        def waiting = value(1, "answer", true)
        switch (key) {
            case "asked of a person":
                return started(question(1, person(), 2), [open(1, BY_PERSON)], null, [], pinStopped)
            case "owed after code was lost":
                return started(codeStep(1, 2), [lost(1, CODE)], null, [], pinStopped)
            case "owed after a person refused":
                return started(question(1, person(), 2), [yielded(1, BY_PERSON, [waiting],
                        [personReview(REVIEWED, [refused(waiting)])], PERSON)], null, [], pinStopped)
            case "values waiting on review":
                return started(question(1, person(), 2), [yielded(1, BY_PERSON, [waiting], [], PERSON)])
            case "failed with its tries spent":
                return started(question(1, model(), 1), [lost(1, BY_MODEL)])
            case "done":
                return started(question(1, person(), 1), [yielded(1, BY_PERSON, [value(1, "answer", false)], [], PERSON)])
            case "not started":
                return unstarted(question(1, person(), 1))
            case "running a call":
                return started(question(1, model(), 1), [attempted(open(1, BY_MODEL))])
            default:
                throw new IllegalArgumentException(key)
        }
    }

    private static StepSnapshot refusedStep(String refusal, int number, int declared) {
        def produced = value(1, "answer", refusal != "refused for its length")
        switch (refusal) {
            case "a person refused on review":
                return started(question(1, person(), declared), after(yielded(number, BY_PERSON, [produced],
                        [personReview(REVIEWED, [refused(produced)])], PERSON)))
            case "refused for its length":
                return started(question(1, model(), declared), after(yielded(number, BY_MODEL, [produced],
                        [lengthReview(REVIEWED, [refused(produced)])])))
            case "the model refused on review":
                return started(question(1, model(), declared, MODEL), after(yielded(number, BY_MODEL, [produced],
                        [modelReview(REVIEWED, [refused(produced)])])))
            case "the model's review went wrong":
                return started(question(1, model(), declared, MODEL), after(yielded(number, BY_MODEL, [produced],
                        [lostReview(REVIEWED)])))
            default:
                throw new IllegalArgumentException(refusal)
        }
    }

    private static TryRecord reviewing(String sent, TryRecord aTry) {
        switch (sent) {
            case "nothing sent": return aTry
            case "its call out": return Runs.sentToReview(aTry, false)
            case "its call turned away": return Runs.sentToReview(aTry, false, ModelCallOutcome.TURNED_AWAY)
            case "too long to send": return Runs.sentToReview(aTry, true)
            case "not built, a list not here": return unbuilt(aTry, ReviewUnbuiltReason.LIST_NOT_HERE)
            case "not built, takes no longer": return unbuilt(aTry, ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED)
            case "not built, gives no longer": return unbuilt(aTry, ReviewUnbuiltReason.NO_LONGER_DECLARED)
            default: throw new IllegalArgumentException(sent)
        }
    }

    /** {@code aTry} with the system's one attempt to review it, nothing sent as it could not be built. */
    private static TryRecord unbuilt(TryRecord aTry, ReviewUnbuiltReason reason) {
        new TryRecord(aTry.id(), aTry.number(), aTry.producer(), aTry.askedBy(), aTry.askedAt(), aTry.endedAt(),
                aTry.endedBy(), aTry.explanation(), aTry.lost(), aTry.fault(), aTry.lostDetail(), aTry.lostDetailCut(),
                aTry.returned(), aTry.values(), aTry.reviews(), aTry.inputs(),
                [new AttemptRecord(Runs.attemptId(70), ModelCallPurpose.REVIEW, true, reason)], [])
    }
}
