package org.lilradish.lite.testutil.inference

import java.util.concurrent.ConcurrentLinkedQueue
import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.domain.inference.TurnAway

/**
 * A model scripted call by call, for specs of whatever calls one. Every script runs through the one
 * loop in {@link #call}, so the order {@link CallProgress} promises holds of every call by
 * construction, and a script that could not happen is refused when it is written. Whether a resend
 * goes ahead is never the script's to say: that is the progress's answer, as it is against a real model.
 */
final class ScriptedModelCalls implements ModelCalls {

    /** Recorded only once the progress has let the send go ahead. */
    static final String SENT = "sent"

    static final String INTERRUPTED_IN_A_SEND = "interrupted while the call was under way"

    final List<CallRequest> requests = Collections.synchronizedList([])

    /** {@link #SENT} for each send, and each turnaway as the model gave it, in the order they happened. */
    final List<Object> events = Collections.synchronizedList([])

    private final Queue<Script> scripts = new ConcurrentLinkedQueue<>()

    ScriptedModelCalls answering(CallOutcome ending) {
        answering([], ending)
    }

    /** Each of {@code resentAfter} is passed on as progress and followed by a resend, if the progress allows one. */
    ScriptedModelCalls answering(List<TurnAway> resentAfter, CallOutcome ending) {
        scripted(new Script(null, resentAfter, ending, false))
    }

    /** As {@link #answering(List, CallOutcome)}, stopping at {@code pause} once the first send is made. */
    ScriptedModelCalls pausing(Pause pause, List<TurnAway> resentAfter, CallOutcome ending) {
        scripted(new Script(pause, resentAfter, ending, false))
    }

    /** The wait after the last of {@code turnedAway} is interrupted, ending the call with nothing more sent. */
    ScriptedModelCalls interruptingTheWaitAfter(List<TurnAway> turnedAway) {
        if (turnedAway.isEmpty()) {
            throw new IllegalArgumentException("a wait only ever follows a turnaway")
        }
        scripted(new Script(null, turnedAway, new CallOutcome.NotResent(), true))
    }

    int scriptsLeft() {
        scripts.size()
    }

    @Override
    CallOutcome call(CallRequest request, CallProgress progress) {
        requests << request
        Script script = scripts.poll()
        if (script == null) {
            throw new AssertionError("call ${requests.size()}, for ${request.purpose()}, has no script left")
        }
        progress.aboutToSend()
        events << SENT
        // Interrupted while a send is under way, a call has gone wrong, which a port returns rather than throws.
        if (script.pause != null && !script.pause.hold()) {
            return new CallOutcome.Errored(INTERRUPTED_IN_A_SEND)
        }
        Iterator<TurnAway> resentAfter = script.resentAfter.iterator()
        while (resentAfter.hasNext()) {
            TurnAway turnAway = resentAfter.next()
            events << turnAway
            progress.turnedAway(turnAway)
            if (script.interruptsLastWait && !resentAfter.hasNext()) {
                Thread.currentThread().interrupt()
                return new CallOutcome.NotResent()
            }
            if (!progress.mayResend()) {
                return new CallOutcome.NotResent()
            }
            events << SENT
        }
        script.ending
    }

    private ScriptedModelCalls scripted(Script script) {
        if (script.resentAfter.any { it.spentUp() }) {
            throw new IllegalArgumentException("a spent-up turnaway is never resent, so it can only end a call")
        }
        if (script.ending instanceof CallOutcome.NotResent && !script.interruptsLastWait) {
            throw new IllegalArgumentException("a call ends not resent only as the progress or an interrupt says")
        }
        scripts << script
        this
    }

    private static final class Script {

        final Pause pause

        final List<TurnAway> resentAfter

        final CallOutcome ending

        final boolean interruptsLastWait

        Script(Pause pause, List<TurnAway> resentAfter, CallOutcome ending, boolean interruptsLastWait) {
            this.pause = pause
            this.resentAfter = List.copyOf(resentAfter)
            this.ending = Objects.requireNonNull(ending, "a script has to say how the call ends")
            this.interruptsLastWait = interruptsLastWait
        }
    }
}
