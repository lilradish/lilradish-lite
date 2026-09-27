package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.workflow.StepProducer;

/**
 * What somebody may do to a step of a run: what it offers, what it withholds and why, and what an act on it
 * refuses are all {@link #refusal}'s answer. The published spelling is written out, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum StepAct {
    /** Fills the try a person may make: the one asked already, or the next, made by answering it here. */
    ANSWER("answer", GroupPermission.ANSWER_STEP),

    /** Asks whoever the step names for the next try, which a person refused the last of. */
    ASK_AGAIN("ask_again", GroupPermission.ANSWER_STEP),

    /** Decides every value of a try waiting on a person's review, each assured or refused. */
    REVIEW("review", GroupPermission.REVIEW_AT_GATE),

    /**
     * Sends again what a model was to be sent and was not: the try a model's step is held back or failed on before
     * anything came of it, or values waiting on the model the step names to review them, which it turned away or is
     * not held here; measured afresh. Whoever may start a run may, since what is sent may cost.
     */
    TRY_SENDING("try_sending", GroupPermission.START_RUN);

    private final String published;

    private final GroupPermission permission;

    StepAct(String published, GroupPermission permission) {
        this.published = published;
        this.permission = permission;
    }

    public String published() {
        return published;
    }

    public GroupPermission permission() {
        return permission;
    }

    /**
     * Why somebody holding the permission may not do this where the step stands, or nothing. Nothing of the
     * kind to do where it is comes first, then the run stopped, then what is the act's own.
     */
    public @Nullable RefusalCode refusal(StepGround standing) {
        requireNonNull(standing, "StepAct standing must not be null");
        StepPosition position = standing.position();
        if (this == REVIEW) {
            if (!(position instanceof StepPosition.AwaitingReview waiting)) {
                return RefusalCode.STEP_MOVED_ON;
            }
            if (standing.runStopped()) {
                return RefusalCode.RUN_STOPPED;
            }
            if (onModel(waiting)) {
                return RefusalCode.REVIEW_NOT_A_PERSONS;
            }
            return standing.readerProduced() ? RefusalCode.REVIEW_OWN_PRODUCTION : null;
        }
        if (this == TRY_SENDING) {
            RefusalCode unsent = unsent(standing);
            if (unsent != null) {
                return unsent;
            }
            if (standing.runStopped()) {
                return RefusalCode.RUN_STOPPED;
            }
            // Reviewing what was produced is not running what the step runs, so a stop on that holds no review.
            return standing.entryStopped() && !reviewing(position) ? RefusalCode.ENTRY_STOPPED : null;
        }
        StepPosition.Owed owed = position.nextTry();
        if (owed == null || (this == ASK_AGAIN && owed.open())) {
            return RefusalCode.STEP_MOVED_ON;
        }
        if (standing.runStopped()) {
            return RefusalCode.RUN_STOPPED;
        }
        if (standing.entryStopped()) {
            return RefusalCode.ENTRY_STOPPED;
        }
        if (this == ASK_AGAIN) {
            boolean asked = standing.producer() == StepProducer.PERSON
                    || standing.producer() == StepProducer.MODEL
                    || standing.codeMayRunAgain();
            return asked ? null : RefusalCode.ASK_AGAIN_NOT_OFFERED;
        }
        return standing.codeGivesOtherwise() != null ? RefusalCode.CODE_STEP_GIVES_OTHERWISE : null;
    }

    /** The acts whose permission is among {@code permitted} and whose {@link #refusal} is nothing. */
    public static Set<StepAct> admitted(Set<GroupPermission> permitted, StepGround standing) {
        requireNonNull(permitted, "StepAct permitted must not be null");
        EnumSet<StepAct> admitted = EnumSet.noneOf(StepAct.class);
        for (StepAct act : values()) {
            if (permitted.contains(act.permission) && act.refusal(standing) == null) {
                admitted.add(act);
            }
        }
        return Collections.unmodifiableSet(admitted);
    }

    /**
     * Each act with something of its kind to do here that this reader may not do, beside what pressing it is
     * refused with; the permission comes first, as the gate refuses it first. Values waiting on a model are no
     * person's to review, so a reader without the permission to is not told that is what keeps them from it.
     */
    public static Map<StepAct, RefusalCode> withheld(Set<GroupPermission> permitted, StepGround standing) {
        requireNonNull(permitted, "StepAct permitted must not be null");
        EnumMap<StepAct, RefusalCode> withheld = new EnumMap<>(StepAct.class);
        for (StepAct act : values()) {
            RefusalCode refusal = act.refusal(standing);
            boolean permits = permitted.contains(act.permission);
            if (refusal == RefusalCode.STEP_MOVED_ON
                    || refusal == RefusalCode.TRY_SENDING_NOT_OFFERED
                    || (act == REVIEW
                            && !permits
                            && standing.position() instanceof StepPosition.AwaitingReview waiting
                            && onModel(waiting))) {
                continue;
            }
            if (!permits) {
                withheld.put(act, RefusalCode.ACT_NOT_PERMITTED);
            } else if (refusal != null) {
                withheld.put(act, refusal);
            }
        }
        return Collections.unmodifiableMap(withheld);
    }

    private static boolean onModel(StepPosition.AwaitingReview waiting) {
        return waiting.values().stream().anyMatch(value -> value.on() == WaitsOn.MODEL);
    }

    /* Nothing of a code step the running release does not hold is sent, by the run or by a press. */
    private static @Nullable RefusalCode unsent(StepGround standing) {
        RefusalCode unsent = unsentWhere(standing);
        return unsent == null && standing.unreleased() ? RefusalCode.TRY_SENDING_NOT_OFFERED : unsent;
    }

    /*
     * A stop, tries spent, or values handed to a person is never settled by sending, so none offers it. A model not
     * held fails whatever it was to be sent for, so that failure is offered whoever produced the try.
     */
    private static @Nullable RefusalCode unsentWhere(StepGround standing) {
        boolean modelProduces = standing.producer() == StepProducer.MODEL;
        return switch (standing.position()) {
            case StepPosition.HeldBack held ->
                modelProduces
                                && (held.reason() == RunStepHoldReason.TOO_LONG
                                        || held.reason() == RunStepHoldReason.TURNED_AWAY)
                        ? null
                        : RefusalCode.TRY_SENDING_NOT_OFFERED;
            case StepPosition.Failed failed ->
                failed.why() instanceof StepFailure.Recorded recorded
                                && (recorded.reason() == RunStepFailureReason.MODEL_NOT_DEPLOYED
                                        || (modelProduces
                                                && recorded.reason() == RunStepFailureReason.UNCUTTABLE_LENGTH))
                        ? null
                        : RefusalCode.TRY_SENDING_NOT_OFFERED;
            case StepPosition.AwaitingReview waiting ->
                switch (waiting.sending()) {
                    case TURNED_AWAY -> null;
                    case TOO_LONG, LIST_NOT_HERE, TAKES_NO_LONGER_DECLARED, NO_LONGER_DECLARED ->
                        RefusalCode.TRY_SENDING_NOT_OFFERED;
                    case UNSENT, OUT -> RefusalCode.STEP_MOVED_ON;
                    case null -> RefusalCode.STEP_MOVED_ON;
                };
            case StepPosition.NotStarted ignored -> RefusalCode.STEP_MOVED_ON;
            case StepPosition.Running ignored -> RefusalCode.STEP_MOVED_ON;
            case StepPosition.Owed ignored -> RefusalCode.STEP_MOVED_ON;
            case StepPosition.Done ignored -> RefusalCode.STEP_MOVED_ON;
        };
    }

    /** Whether what would be sent again is values to be reviewed: turned away, or failed on the reviewer not held. */
    private static boolean reviewing(StepPosition position) {
        return position instanceof StepPosition.AwaitingReview
                || (position instanceof StepPosition.Failed failed
                        && failed.why() instanceof StepFailure.Recorded recorded
                        && recorded.purpose() == ModelCallPurpose.REVIEW);
    }
}
