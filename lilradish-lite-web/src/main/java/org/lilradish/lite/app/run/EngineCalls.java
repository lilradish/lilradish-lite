package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.inference.AnswerReader;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.inference.CallOutcome;
import org.lilradish.lite.domain.inference.CallProgress;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.inference.DoesNotFit;
import org.lilradish.lite.domain.inference.ForeignProse;
import org.lilradish.lite.domain.inference.KeptAnswer;
import org.lilradish.lite.domain.inference.ModelCallOutcome;
import org.lilradish.lite.domain.inference.ModelCalls;
import org.lilradish.lite.domain.inference.ProductionAnswer;
import org.lilradish.lite.domain.inference.ReviewAnswer;
import org.lilradish.lite.domain.inference.TurnAway;
import org.lilradish.lite.domain.inference.Unwrapping;
import org.lilradish.lite.domain.run.ModelCallId;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.ReviewOutcome;
import org.lilradish.lite.domain.run.RunStepHoldReason;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.TryLostReason;
import org.lilradish.lite.domain.wire.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place a model is called: on the engine's own threads, outside any transaction, so nothing is held
 * while it answers. What happens while the call is out is written as it happens, each in a short transaction
 * of its own under the tree's lock; how it ended is written once, in one transaction of its own, so nothing
 * planned after it can take back what came back.
 */
@Component
final class EngineCalls {

    private static final Logger logger = LoggerFactory.getLogger(EngineCalls.class);

    private final ModelCalls modelCalls;

    private final TransactionOperations transactions;

    private final RunTree tree;

    private final EngineWrites writes;

    private final CeilingReach ceilings;

    EngineCalls(
            ModelCalls modelCalls,
            TransactionOperations transactions,
            RunTree tree,
            EngineWrites writes,
            CeilingReach ceilings) {
        this.modelCalls = modelCalls;
        this.transactions = transactions;
        this.tree = tree;
        this.writes = writes;
        this.ceilings = ceilings;
    }

    /**
     * Makes {@code pending} and writes how it ended, returning once that is committed or found ended already. What
     * the progress failed to write ends the call as not sent again, since only a turnaway is followed by writing;
     * anything else failing on this side ends it gone wrong, naming only what kind of failure it was.
     */
    void callAndEnd(PendingCall pending) {
        requireNonNull(pending, "EngineCalls pending must not be null");
        if (!(Thread.currentThread() instanceof EngineThread)) {
            throw new IllegalStateException("Call " + pending.call().value() + " was to be made off the run engine");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Call " + pending.call().value() + " was to be made inside a transaction it would hold open");
        }
        CallOutcome outcome;
        try {
            outcome = modelCalls.call(pending.request(), new Progress(pending));
        } catch (ProgressFailed failed) {
            logger.warn(
                    "Call {} of try {} of run {} is not sent again: writing what happened to it failed with {}",
                    pending.call().value(),
                    pending.aTry().value(),
                    pending.run().value(),
                    requireNonNull(failed.getCause()).getClass().getName());
            outcome = new CallOutcome.NotResent();
        } catch (RuntimeException failed) {
            outcome = failedHere(pending, failed);
        }
        Landed landed = null;
        if (outcome instanceof CallOutcome.CameBack cameBack) {
            try {
                landed = landed(pending, cameBack);
            } catch (RuntimeException failed) {
                outcome = failedHere(pending, failed);
            }
        }
        ended(pending, outcome, landed);
    }

    /* What failed may say what was sent or what came back, so only its kind is kept, and logged beside the keys. */
    private static CallOutcome failedHere(PendingCall pending, RuntimeException failed) {
        String kind = failed.getClass().getName();
        logger.warn(
                "Call {} of try {} of run {} failed on this side with {}",
                pending.call().value(),
                pending.aTry().value(),
                pending.run().value(),
                kind);
        return new CallOutcome.Errored("The call failed on this side with " + kind + ".");
    }

    private void ended(PendingCall pending, CallOutcome outcome, @Nullable Landed landed) {
        transactions.executeWithoutResult(status -> {
            LockedTree held = locked(pending);
            boolean reviewing = pending.reading() instanceof PendingCall.Reviewing;
            boolean ended =
                    switch (outcome) {
                        case CallOutcome.CameBack cameBack -> cameBack(held, pending, cameBack, requireNonNull(landed));
                        case CallOutcome.Errored errored -> errored(held, pending, errored);
                        case CallOutcome.TurnedAway turnedAway ->
                            reviewing
                                    ? endedTurnedAway(held, pending.call(), turnedAway.last())
                                    : turnedAway(held, pending.call(), pending.attempt(), turnedAway.last());
                        case CallOutcome.NotResent ignored ->
                            reviewing
                                    ? endedTurnedAway(held, pending.call(), null)
                                    : notResent(held, pending.call(), pending.attempt());
                    };
            if (!ended) {
                logger.warn(
                        "Call {} of try {} of run {} had ended already; how it ended now is dropped",
                        pending.call().value(),
                        pending.aTry().value(),
                        pending.run().value());
            }
        });
    }

    /*
     * Read once as it arrived, from what came back rather than from what is kept of it; an answer not kept as it
     * came does not fit, even where its values or decisions do, and is cut off first of all.
     */
    private static Landed landed(PendingCall pending, CallOutcome.CameBack cameBack) {
        KeptAnswer kept = KeptAnswer.of(cameBack.answer());
        if (cameBack.cutOff()) {
            return new Landed(kept, DidNotFitReason.CUT_OFF, null, null);
        }
        JsonValue read = AnswerReader.read(cameBack.answer());
        return switch (pending.reading()) {
            case PendingCall.Producing producing ->
                switch (Unwrapping.production(producing.gives(), read)) {
                    case DoesNotFit misfit -> new Landed(kept, misfit.reason(), null, null);
                    case ProductionAnswer.Produced produced ->
                        kept.altered()
                                ? new Landed(kept, DidNotFitReason.NOT_KEPT_AS_IT_CAME, null, null)
                                : new Landed(kept, null, produced, null);
                };
            case PendingCall.Reviewing reviewing ->
                switch (Unwrapping.review(reviewing.deciding(), read)) {
                    case DoesNotFit misfit -> new Landed(kept, misfit.reason(), null, null);
                    case ReviewAnswer.Reviewed reviewed ->
                        kept.altered()
                                ? new Landed(kept, DidNotFitReason.NOT_KEPT_AS_IT_CAME, null, null)
                                : new Landed(kept, null, null, reviewed);
                };
        };
    }

    private boolean cameBack(LockedTree held, PendingCall pending, CallOutcome.CameBack cameBack, Landed landed) {
        if (!writes.cameBack(held, pending.call(), cameBack, landed.kept())) {
            return false;
        }
        DidNotFitReason misfit = landed.misfit();
        TryLostReason lost = misfit == null ? null : TryLostReason.DID_NOT_FIT;
        switch (pending.reading()) {
            case PendingCall.Producing producing -> {
                writes.modelTryEnded(
                        held,
                        pending.aTry(),
                        pending.call(),
                        ModelCallOutcome.CAME_BACK,
                        landed.kept().altered(),
                        lost,
                        misfit);
                ProductionAnswer.Produced produced = landed.produced();
                if (produced != null) {
                    for (int index = 0; index < producing.gives().size(); index++) {
                        FieldName field = producing.gives().get(index).name();
                        writes.modelValue(
                                held,
                                pending.aTry(),
                                producing.givesFields().get(index),
                                requireNonNull(produced.values().get(field), "an answer that fits gives every field"),
                                produced.confidences().get(field));
                    }
                }
            }
            case PendingCall.Reviewing reviewing ->
                writes.reviewedByModel(
                        held,
                        pending.aTry(),
                        pending.call(),
                        ModelCallOutcome.CAME_BACK,
                        landed.kept().altered(),
                        lost,
                        misfit,
                        decided(reviewing, landed.reviewed()));
        }
        return true;
    }

    /* Each value asked about, as the model decided it; none where its answer did not fit, which decides nothing. */
    private static List<EngineWrites.DecidedRecord> decided(
            PendingCall.Reviewing reviewing, ReviewAnswer.@Nullable Reviewed reviewed) {
        if (reviewed == null) {
            return List.of();
        }
        List<EngineWrites.DecidedRecord> decided =
                new ArrayList<>(reviewing.deciding().size());
        for (int index = 0; index < reviewing.deciding().size(); index++) {
            ReviewAnswer.Decision decision = requireNonNull(
                    reviewed.decisions().get(reviewing.deciding().get(index)), "a review that fits decides each value");
            decided.add(
                    switch (decision) {
                        case ReviewAnswer.Assured ignored ->
                            new EngineWrites.DecidedRecord(reviewing.values().get(index), ReviewOutcome.ASSURED, null);
                        case ReviewAnswer.Refused refused ->
                            new EngineWrites.DecidedRecord(
                                    reviewing.values().get(index), ReviewOutcome.REFUSED, refused.words());
                    });
        }
        return decided;
    }

    private boolean errored(LockedTree held, PendingCall pending, CallOutcome.Errored errored) {
        if (!writes.errored(held, pending.call(), ForeignProse.errorDetail(errored.detail()))) {
            return false;
        }
        switch (pending.reading()) {
            case PendingCall.Producing ignored ->
                writes.modelTryEnded(
                        held,
                        pending.aTry(),
                        pending.call(),
                        ModelCallOutcome.ERRORED,
                        false,
                        TryLostReason.ERRORED,
                        null);
            case PendingCall.Reviewing ignored ->
                writes.reviewedByModel(
                        held,
                        pending.aTry(),
                        pending.call(),
                        ModelCallOutcome.ERRORED,
                        false,
                        TryLostReason.ERRORED,
                        null,
                        List.of());
        }
        return true;
    }

    /**
     * Inside the caller's transaction, the tree held: {@code call}, out when this system stopped, ended as nothing
     * came back and {@code aTry} lost with it, a try spent; what was sent stays counted as measured here. False where
     * the call had ended already, and then nothing is written.
     */
    boolean nothingCameBack(LockedTree held, ModelCallId call, ProductionId aTry) {
        if (!writes.nothingCameBack(held, call)) {
            return false;
        }
        writes.modelTryEnded(
                held, aTry, call, ModelCallOutcome.NOTHING_CAME_BACK, false, TryLostReason.NOTHING_CAME_BACK, null);
        return true;
    }

    /**
     * Inside the caller's transaction, the tree held: {@code call}, out to review {@code reviewed} when this system
     * stopped, ended as nothing came back and the review lost with it, which spends the try as a refusal would; what
     * was sent stays counted as measured here. False where the call had ended already, and then nothing is written.
     */
    boolean reviewNothingCameBack(LockedTree held, ModelCallId call, ProductionId reviewed) {
        if (!writes.nothingCameBack(held, call)) {
            return false;
        }
        writes.reviewedByModel(
                held,
                reviewed,
                call,
                ModelCallOutcome.NOTHING_CAME_BACK,
                false,
                TryLostReason.NOTHING_CAME_BACK,
                null,
                List.of());
        return true;
    }

    /**
     * Inside the caller's transaction, the tree held: {@code call} ended as turned away and not sent again, as a call
     * whose resend was refused ends. False where the call had ended already, and then nothing is written.
     */
    boolean notResent(LockedTree held, ModelCallId call, RunStepSendAttemptId attempt) {
        return turnedAway(held, call, attempt, null);
    }

    /**
     * Inside the caller's transaction, the tree held: {@code call}, out to review a try, ended as turned away and not
     * sent again, holding nothing. False where the call had ended already, and then nothing is written.
     */
    boolean reviewNotResent(LockedTree held, ModelCallId call) {
        return endedTurnedAway(held, call, null);
    }

    /* The try stays open, spending nothing: the step is held on the attempt, to be tried sending again. */
    private boolean turnedAway(
            LockedTree held, ModelCallId call, RunStepSendAttemptId attempt, @Nullable TurnAway last) {
        if (!endedTurnedAway(held, call, last)) {
            return false;
        }
        writes.heldOn(held, attempt, RunStepHoldReason.TURNED_AWAY);
        return true;
    }

    /* Spending nothing and holding nothing, as a review turned away is: its values wait on the model still. */
    private boolean endedTurnedAway(LockedTree held, ModelCallId call, @Nullable TurnAway last) {
        if (!writes.turnedAway(held, call)) {
            return false;
        }
        if (last != null) {
            writes.turnaway(held, call, ForeignProse.said(last.said()), last.spentUp(), true);
        }
        return true;
    }

    private LockedTree locked(PendingCall pending) {
        return tree.lock(pending.group(), pending.root())
                .orElseThrow(() -> new IllegalStateException("Group "
                        + pending.group().value() + " holds no run "
                        + pending.root().value() + " for call " + pending.call().value()));
    }

    /** What is written as the call goes, each step on record before the next is taken. */
    private final class Progress implements CallProgress {

        private final PendingCall pending;

        Progress(PendingCall pending) {
            this.pending = pending;
        }

        /* Written down as sent just before, in a transaction of its own, so a stop landing now still lets it go. */
        @Override
        public void aboutToSend() {}

        @Override
        public void turnedAway(TurnAway turnAway) {
            requireNonNull(turnAway, "EngineCalls turnaway must not be null");
            try {
                transactions.executeWithoutResult(status -> writes.turnaway(
                        locked(pending),
                        pending.call(),
                        ForeignProse.said(turnAway.said()),
                        turnAway.spentUp(),
                        false));
            } catch (RuntimeException failed) {
                throw new ProgressFailed(failed);
            }
        }

        /*
         * Never while a stop is in force, nor once the ceiling is reached, which stops the tree; the call itself is
         * left out of that count, as it was when written down as sent, since sending it again makes no other call.
         */
        @Override
        public boolean mayResend() {
            try {
                return Boolean.TRUE.equals(transactions.execute(status -> {
                    LockedTree held = locked(pending);
                    if (writes.stoppedWhileOut(held, pending.call()) || ceilings.stopsAt(held, pending.call())) {
                        return false;
                    }
                    writes.resent(held, pending.call());
                    return true;
                }));
            } catch (RuntimeException failed) {
                throw new ProgressFailed(failed);
            }
        }
    }

    /** A failure of the progress's own writing, told apart from anything else thrown out of a call. */
    private static final class ProgressFailed extends RuntimeException {

        ProgressFailed(RuntimeException cause) {
            super(null, cause, false, false);
        }
    }

    /**
     * How an answer that came back was read, before anything is written.
     *
     * @param misfit why it does not fit; none where it does
     * @param produced its values, exactly where it fits an answer to produce
     * @param reviewed its decisions, exactly where it fits an answer to review
     */
    private record Landed(
            KeptAnswer kept,
            @Nullable DidNotFitReason misfit,
            ProductionAnswer.@Nullable Produced produced,
            ReviewAnswer.@Nullable Reviewed reviewed) {}
}
