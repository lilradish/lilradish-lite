package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.codestep.CodeRuns;
import org.lilradish.lite.domain.codestep.CodeCall;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.codestep.CodeErrorReason;
import org.lilradish.lite.domain.codestep.CodeOutcome;
import org.lilradish.lite.domain.codestep.CodeStepDeclaration;
import org.lilradish.lite.domain.codestep.CodeStepName;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.run.CodeStepFit;
import org.lilradish.lite.domain.run.EngineAct;
import org.lilradish.lite.domain.run.EngineActs;
import org.lilradish.lite.domain.run.InputRecord;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunStepId;
import org.lilradish.lite.domain.run.StepAct;
import org.lilradish.lite.domain.run.StepGround;
import org.lilradish.lite.domain.run.StepInputs;
import org.lilradish.lite.domain.run.StepPosition;
import org.lilradish.lite.domain.run.StepPositions;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.StepProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place a code step is run: on the engine's code threads, outside any transaction. Its try is written in a
 * short transaction of its own just before, and only while it is still the try to make, so a try on record is code
 * run and none is code never run; how it ended is written in another, so nothing planned after it takes that back.
 */
@Component
final class EngineCodes {

    private static final Logger logger = LoggerFactory.getLogger(EngineCodes.class);

    private final CodeRuns codeRuns;

    private final TransactionOperations transactions;

    private final RunTree tree;

    private final RunSnapshots snapshots;

    private final EngineWrites writes;

    private final EngineExecutor executor;

    EngineCodes(
            CodeRuns codeRuns,
            TransactionOperations transactions,
            RunTree tree,
            RunSnapshots snapshots,
            EngineWrites writes,
            EngineExecutor executor) {
        this.codeRuns = codeRuns;
        this.transactions = transactions;
        this.tree = tree;
        this.snapshots = snapshots;
        this.writes = writes;
        this.executor = executor;
    }

    /**
     * Makes {@code pending} where it is still the try to make and writes how it ended, returning once that is
     * committed or found ended already; whether its try was written, one ended at once as no longer fitting what the
     * release declares included, its code then never run.
     */
    boolean runAndEnd(PendingCode pending) {
        requireNonNull(pending, "EngineCodes pending must not be null");
        if (!(Thread.currentThread() instanceof CodeThread)) {
            throw new IllegalStateException(
                    "Step " + pending.step().value() + " was to run code off the run engine's code threads");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Step " + pending.step().value() + " was to run code inside a transaction it would hold open");
        }
        Begun begun = transactions.execute(status -> begun(pending));
        if (begun == null) {
            return false;
        }
        CodeCall call = begun.call();
        if (call == null) {
            return true;
        }
        CodeOutcome outcome;
        try {
            outcome = codeRuns.run(call);
        } catch (Error fatal) {
            // The try is left open, for whatever sweeps what a stopped system left out; it may say what code said.
            logger.error(
                    "Try {} (number {}) of step {} of run {} under run {} of group {} stopped its thread with {}",
                    begun.aTry().value(),
                    pending.number(),
                    pending.step().value(),
                    pending.run().value(),
                    pending.root().value(),
                    pending.group().value(),
                    fatal.getClass().getName());
            throw fatal;
        } catch (RuntimeException failed) {
            // Whatever failed may say what the code took, so only its kind is logged beside the keys, and kept nowhere.
            logger.warn(
                    "Try {} of run {} failed on this side with {}",
                    begun.aTry().value(),
                    pending.run().value(),
                    failed.getClass().getName());
            outcome = new CodeOutcome.Errored(
                    new CodeError.Fault(CodeErrorReason.FAILED_ON_THIS_SIDE, List.of(), null, null), null);
        }
        CodeOutcome ended = outcome;
        transactions.executeWithoutResult(status -> {
            if (!writes.codeEnded(locked(pending), begun.aTry(), ended)) {
                logger.warn(
                        "Try {} of run {} was ended before its code came back; what it came to is dropped",
                        begun.aTry().value(),
                        pending.run().value());
            }
        });
        return true;
    }

    private @Nullable Begun begun(PendingCode pending) {
        LockedTree held = locked(pending);
        RunSnapshot run = snapshots.locked(held, pending.run());
        StepSnapshot step = run.step(pending.step())
                .orElseThrow(
                        () -> new IllegalStateException("Run " + pending.run().value() + " holds no step "
                                + pending.step().value()));
        // Judged once the lock is held: stopping may have begun while this waited for it.
        if (executor.stopping() || !due(run, step, pending)) {
            return null;
        }
        StepRuns.Code code = (StepRuns.Code) step.planned().runs();
        ReleasedCodeStep released = requireNonNull(code.released(), "a code step started is one the release holds");
        if (pending.number() > 1 && !released.mayRunAgain()) {
            throw new IllegalStateException(
                    "Code step " + code.codeStep() + " was to be run again, which the release does not let it be");
        }
        RunStepId runStep = writes.runStep(held, run, step);
        List<StepInputs.BindingRecord> bound = StepInputs.bound(run, step)
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + step.planned().name().value() + " was reached before what it takes stood"));
        Optional<CodeError.Fault> misfit = CodeStepFit.misfit(run, step);
        if (misfit.isPresent()) {
            ProductionId aTry = writes.tried(
                    held,
                    run,
                    runStep,
                    pending.number(),
                    StepProducer.CODE,
                    pending.asker(),
                    released.mayRunAgain(),
                    untraced(bound));
            CodeOutcome errored = new CodeOutcome.Errored(misfit.get(), null);
            if (!writes.codeEnded(held, aTry, errored)) {
                throw new IllegalStateException("Try " + aTry.value() + " was ended as it was written");
            }
            return new Begun(aTry, null);
        }
        CodeStepDeclaration declared = released.declared();
        List<InputRecord> inputs = StepInputs.traced(run, step, declared.takes())
                .orElseThrow(() -> new IllegalStateException(
                        "Step " + step.planned().name().value() + " was reached before what it takes stood"));
        ProductionId aTry = writes.tried(
                held,
                run,
                runStep,
                pending.number(),
                StepProducer.CODE,
                pending.asker(),
                released.mayRunAgain(),
                inputs);
        return new Begun(
                aTry,
                new CodeCall(
                        new CodeStepName(code.codeStep()),
                        declared,
                        released.lists(),
                        StepInputs.filled(declared.takes(), bound)));
    }

    /*
     * Worked out again under the lock, since anything may have happened since it was handed over: the run's own
     * next act is still this try, or the one a person asked for is still theirs to ask. Either refuses a run stopped.
     */
    private static boolean due(RunSnapshot run, StepSnapshot step, PendingCode pending) {
        if (!(step.planned().runs() instanceof StepRuns.Code)
                || !(step.planned().producer() instanceof Producer.Code)) {
            throw new IllegalStateException("Step " + step.planned().name().value() + " runs no code of its own");
        }
        if (pending.asker() == null) {
            return EngineActs.next(run, StepPositions.of(run))
                    .equals(new EngineAct.StartTry(step.planned(), pending.number()));
        }
        StepPosition position = StepPositions.of(run, step);
        StepPosition.Owed owed = position.nextTry();
        return owed != null
                && owed.number() == pending.number()
                && StepAct.ASK_AGAIN.refusal(StepGround.of(run, step, position, null)) == null;
    }

    /* Each binding beside what it was read from, kept though what it fills is no longer declared. */
    private static List<InputRecord> untraced(List<StepInputs.BindingRecord> bound) {
        return bound.stream()
                .map(each -> new InputRecord(each.binding().id(), each.source()))
                .toList();
    }

    private LockedTree locked(PendingCode pending) {
        return tree.lock(pending.group(), pending.root())
                .orElseThrow(() -> new IllegalStateException("Group "
                        + pending.group().value() + " holds no run "
                        + pending.root().value() + " for step " + pending.step().value()));
    }

    /**
     * A try written, and what its code is run with; none where it no longer fit and was ended at once.
     */
    private record Begun(ProductionId aTry, @Nullable CodeCall call) {}
}
