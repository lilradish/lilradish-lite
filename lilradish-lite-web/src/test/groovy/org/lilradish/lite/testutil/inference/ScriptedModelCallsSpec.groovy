package org.lilradish.lite.testutil.inference

import static org.lilradish.lite.testutil.inference.ScriptedModelCalls.INTERRUPTED_IN_A_SEND
import static org.lilradish.lite.testutil.inference.ScriptedModelCalls.SENT

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.inference.TurnAway
import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.model.SentText
import spock.lang.AutoCleanup
import spock.lang.Specification

/** Every engine spec believes this fake, so the order it keeps is proved here rather than trusted. */
class ScriptedModelCallsSpec extends Specification {

    static final TurnAway BUSY = new TurnAway("busy, try later", false)

    static final TurnAway STILL_BUSY = new TurnAway(null, false)

    static final TurnAway SPENT_UP = new TurnAway("quota spent", true)

    static final CallOutcome CAME_BACK = new CallOutcome.CameBack('{"total":"12"}', 5, 2, true, false)

    ScriptedModelCalls model = new ScriptedModelCalls()

    CallProgress progress = Mock()

    @AutoCleanup("shutdownNow")
    ExecutorService caller = Executors.newSingleThreadExecutor()

    def "a call scripted to end at once is sent once, told first, and ends as scripted"() {
        given:
        model.answering(ending)

        when:
        def outcome = model.call(request(ModelCallPurpose.PRODUCE), progress)

        then:
        1 * progress.aboutToSend()
        0 * _

        and:
        outcome.is(ending)
        model.events == [SENT]
        model.scriptsLeft() == 0

        where:
        ending << [CAME_BACK, new CallOutcome.Errored("upstream refused"), new CallOutcome.TurnedAway(SPENT_UP)]
    }

    def "each turnaway a resend follows is passed on, then asked about, and only then is the resend made"() {
        given:
        def last = new CallOutcome.TurnedAway(SPENT_UP)
        model.answering([BUSY, STILL_BUSY], last)

        when:
        def outcome = model.call(request(ModelCallPurpose.REVIEW), progress)

        then:
        1 * progress.aboutToSend()

        then:
        1 * progress.turnedAway(BUSY)

        then:
        1 * progress.mayResend() >> true

        then:
        1 * progress.turnedAway(STILL_BUSY)

        then:
        1 * progress.mayResend() >> true
        0 * _

        and: "the turnaway that ended it is returned and never passed on"
        outcome.is(last)
        model.events == [SENT, BUSY, SENT, STILL_BUSY, SENT]
    }

    def "a resend the progress does not allow is not made, and the call ends not resent"() {
        given:
        model.answering([BUSY, STILL_BUSY], CAME_BACK)

        when:
        def outcome = model.call(request(ModelCallPurpose.PRODUCE), progress)

        then:
        1 * progress.aboutToSend()

        then:
        1 * progress.turnedAway(BUSY)

        then:
        1 * progress.mayResend() >> false
        0 * _

        and:
        outcome instanceof CallOutcome.NotResent
        model.events == [SENT, BUSY]
    }

    def "a failure in the progress is thrown as it is, and nothing more is sent"() {
        given:
        def failure = new IllegalStateException("the call has already ended")
        model.answering([BUSY], CAME_BACK)
        progress.aboutToSend() >> { if (failing == "aboutToSend") throw failure }
        progress.turnedAway(_) >> { if (failing == "turnedAway") throw failure }
        progress.mayResend() >> { if (failing == "mayResend") throw failure; true }

        when:
        model.call(request(ModelCallPurpose.PRODUCE), progress)

        then:
        def raised = thrown(IllegalStateException)
        raised.is(failure)
        model.events == events

        where:
        failing       || events
        "aboutToSend" || []
        "turnedAway"  || [SENT, BUSY]
        "mayResend"   || [SENT, BUSY]
    }

    def "a paused call stops once its first send is made, and goes on only when released"() {
        given:
        def pause = new Pause()
        model.pausing(pause, [BUSY], CAME_BACK)
        def allowing = Stub(CallProgress) { mayResend() >> true }

        when:
        def call = caller.submit({ model.call(request(ModelCallPurpose.PRODUCE), allowing) } as Callable)
        pause.awaitReached()
        def heldAt = List.copyOf(model.events)
        def doneWhileHeld = call.done
        pause.release()
        def outcome = call.get(10, TimeUnit.SECONDS)

        then:
        heldAt == [SENT]
        !doneWhileHeld
        outcome.is(CAME_BACK)
        model.events == [SENT, BUSY, SENT]
    }

    def "a paused call interrupted mid-send ends errored, its thread left interrupted, and nothing more is sent"() {
        given:
        def pause = new Pause()
        model.pausing(pause, [BUSY], CAME_BACK)
        def outcome = new AtomicReference<CallOutcome>()
        def interruptedAfter = new AtomicBoolean()

        when:
        def calling = Thread.start {
            outcome.set(model.call(request(ModelCallPurpose.PRODUCE), progress))
            interruptedAfter.set(Thread.currentThread().isInterrupted())
        }
        pause.awaitReached()
        calling.interrupt()
        calling.join(10_000)

        then:
        1 * progress.aboutToSend()
        0 * _

        and:
        outcome.get() == new CallOutcome.Errored(INTERRUPTED_IN_A_SEND)
        interruptedAfter.get()
        model.events == [SENT]
    }

    def "a wait that is interrupted ends the call not resent, the thread left interrupted and nothing asked"() {
        given:
        model.interruptingTheWaitAfter([BUSY, STILL_BUSY])

        when:
        def outcome = model.call(request(ModelCallPurpose.HELP), progress)
        def interrupted = Thread.interrupted()

        then:
        1 * progress.aboutToSend()
        1 * progress.turnedAway(BUSY)
        1 * progress.mayResend() >> true
        1 * progress.turnedAway(STILL_BUSY)
        0 * _

        and:
        outcome instanceof CallOutcome.NotResent
        interrupted
        model.events == [SENT, BUSY, SENT, STILL_BUSY]
    }

    def "an interrupted wait has to follow a turnaway"() {
        when:
        model.interruptingTheWaitAfter([])

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "a wait only ever follows a turnaway"
        model.scriptsLeft() == 0
    }

    def "calls take their scripts in order, each taken script no longer left, and every request is kept in order"() {
        given:
        def errored = new CallOutcome.Errored("upstream refused")
        model.answering(CAME_BACK).answering(errored)
        def first = request(ModelCallPurpose.PRODUCE)
        def second = request(ModelCallPurpose.REVIEW)

        when:
        def firstOutcome = model.call(first, progress)
        def leftBetween = model.scriptsLeft()
        def secondOutcome = model.call(second, progress)

        then:
        firstOutcome.is(CAME_BACK)
        secondOutcome.is(errored)
        leftBetween == 1
        model.scriptsLeft() == 0
        model.requests == [first, second]
        model.events == [SENT, SENT]
    }

    def "a call with no script left fails loudly, naming which call it was, and sends nothing"() {
        given:
        model.answering(CAME_BACK)
        model.call(request(ModelCallPurpose.PRODUCE), Stub(CallProgress))

        when:
        model.call(request(ModelCallPurpose.HELP), progress)

        then:
        def error = thrown(AssertionError)
        error.message == "call 2, for HELP, has no script left"
        0 * progress._
        model.events == [SENT]
        model.requests.size() == 2
    }

    def "a spent-up turnaway cannot be scripted to be resent, wherever it stands"() {
        when:
        model.answering(resentAfter, CAME_BACK)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "a spent-up turnaway is never resent, so it can only end a call"
        model.scriptsLeft() == 0

        where:
        resentAfter << [[SPENT_UP], [BUSY, SPENT_UP], [SPENT_UP, BUSY]]
    }

    def "a call cannot be scripted to end not resent, which is the progress's to cause"() {
        when:
        model.answering([BUSY], new CallOutcome.NotResent())

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "a call ends not resent only as the progress or an interrupt says"
        model.scriptsLeft() == 0
    }

    def "a script has to say how the call ends"() {
        when:
        model.answering(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "a script has to say how the call ends"
        model.scriptsLeft() == 0
    }

    private static CallRequest request(ModelCallPurpose purpose) {
        def model = new DeployedModel(new ModelName("sample_model"), [], 200000, new BigDecimal("4"), 8192, [])
        new CallRequest(model, null, purpose, SentText.measure("answer in JSON", "the invoice"))
    }
}
