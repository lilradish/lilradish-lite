package org.lilradish.lite.testutil.codestep

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.lilradish.lite.domain.codestep.CodeStep
import org.lilradish.lite.domain.codestep.CodeStepDeclaration
import org.lilradish.lite.domain.codestep.CodeStepName
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.wire.JsonValue
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * A code step a spec scripts: what it gives back, or throws, for what it takes. It counts its runs and says, of each,
 * which thread ran it and whether a transaction was open there; where a gate is given, each run waits on it first.
 */
final class ScriptedCodeStep implements CodeStep {

    /** Long past any spec's own wait, so a gate never opened fails the run rather than hanging the build. */
    static final long LONGEST_GATE_SECONDS = 60

    final AtomicInteger runs = new AtomicInteger()

    final List<Ran> ran = new CopyOnWriteArrayList<>()

    private final CodeStepName name

    private final CodeStepDeclaration declared

    private final Closure<JsonValue.JsonObject> behaviour

    private final CountDownLatch gate

    ScriptedCodeStep(String name, List<Field> takes, List<Field> gives, boolean mayRunAgain,
                     Closure<JsonValue.JsonObject> behaviour, CountDownLatch gate = null) {
        this.name = new CodeStepName(name)
        this.declared = new CodeStepDeclaration(
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), takes),
                new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), gives),
                mayRunAgain)
        this.behaviour = behaviour
        this.gate = gate
    }

    @Override
    CodeStepName name() {
        name
    }

    @Override
    CodeStepDeclaration declaration() {
        declared
    }

    @Override
    JsonValue.JsonObject run(JsonValue.JsonObject takes) {
        ran << new Ran(Thread.currentThread().name, TransactionSynchronizationManager.isActualTransactionActive())
        runs.incrementAndGet()
        if (gate != null && !gate.await(LONGEST_GATE_SECONDS, TimeUnit.SECONDS)) {
            throw new GateNeverOpened("ScriptedCodeStep ${name.value()} was never let through its gate")
        }
        behaviour.call(takes)
    }

    /** An error nothing running a code step catches, so a spec whose gate never opens fails rather than erring. */
    static final class GateNeverOpened extends Error {

        GateNeverOpened(String message) {
            super(message)
        }
    }

    /** One run as it began: on which thread, and whether a transaction was open there. */
    record Ran(String thread, boolean inTransaction) {}
}
