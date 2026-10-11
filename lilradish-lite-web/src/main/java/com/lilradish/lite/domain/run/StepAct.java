package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.inference.ModelCallPurpose;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Resource offers and refusals share the writer's state checks; permission and self-review are checked separately.
 * Published spellings are explicit for the reason {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum StepAct {
    /** Fills the try a person may make: the one asked already, or the next, made by answering it here. */
    ANSWER("answer", GroupPermission.ANSWER_STEP),

    /** Asks whoever the step names for the next try, which a person refused the last of. */
    ASK_AGAIN("ask_again", GroupPermission.ANSWER_STEP),

    /** Decides every value of a try waiting on a person's review, each assured or refused. */
    REVIEW("review", GroupPermission.REVIEW_AT_GATE),

    /**
     * Retries sending a model request, or hands an unbuildable review to a person.
     * Starting a run is required because retrying a model request may incur cost.
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
        RefusalCode state = stateRefusal(standing.state());
        if (state != null) {
            return state;
        }
        return this == REVIEW && standing.readerProduced() ? RefusalCode.REVIEW_OWN_PRODUCTION : null;
    }

    /** Before permission and self-review checks. */
    public static Set<StepAct> stateActs(StepGround.State standing) {
        requireNonNull(standing, "StepAct standing must not be null");
        EnumSet<StepAct> acts = EnumSet.noneOf(StepAct.class);
        for (StepAct act : values()) {
            if (act.stateRefusal(standing) == null) {
                acts.add(act);
            }
        }
        return Collections.unmodifiableSet(acts);
    }

    /** State reasons worth explaining, independent of permission and the actual reader. */
    public static Map<StepAct, RefusalCode> stateRefusals(StepGround.State standing) {
        requireNonNull(standing, "StepAct standing must not be null");
        EnumMap<StepAct, RefusalCode> refusals = new EnumMap<>(StepAct.class);
        for (StepAct act : values()) {
            RefusalCode refusal = act.stateRefusal(standing);
            if (refusal != null
                    && refusal != RefusalCode.STEP_MOVED_ON
                    && refusal != RefusalCode.TRY_SENDING_NOT_OFFERED) {
                refusals.put(act, refusal);
            }
        }
        return Collections.unmodifiableMap(refusals);
    }

    private @Nullable RefusalCode stateRefusal(StepGround.State standing) {
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
            return null;
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

    private static boolean onModel(StepPosition.AwaitingReview waiting) {
        return waiting.values().stream().anyMatch(value -> value.on() == WaitsOn.MODEL);
    }

    /* Missing code cannot produce; a failed review may be tried to pass its yielded values to a person. */
    private static @Nullable RefusalCode unsent(StepGround.State standing) {
        RefusalCode unsent = unsentWhere(standing);
        if (unsent != null || !standing.unreleased()) {
            return unsent;
        }
        if (standing.producer() == StepProducer.CODE
                && standing.position() instanceof StepPosition.Failed failed
                && failed.why() instanceof StepFailure.Recorded recorded
                && recorded.reason() == RunStepFailureReason.MODEL_NOT_DEPLOYED
                && recorded.purpose() == ModelCallPurpose.REVIEW) {
            return null;
        }
        return RefusalCode.TRY_SENDING_NOT_OFFERED;
    }

    /*
     * A stop, tries spent, or values handed to a person is never settled by sending, so none offers it. A model not
     * held fails whatever it was to be sent for, so that failure is offered whoever produced the try.
     */
    private static @Nullable RefusalCode unsentWhere(StepGround.State standing) {
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
                                && (recorded.reason() == RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES
                                        || recorded.reason() == RunStepFailureReason.MODEL_NOT_DEPLOYED
                                        || (modelProduces
                                                && (recorded.reason() == RunStepFailureReason.UNCUTTABLE_LENGTH
                                                        || recorded.reason() == RunStepFailureReason.TOOL_NOT_OFFERED
                                                        || recorded.reason()
                                                                == RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
                                                        || recorded.reason()
                                                                == RunStepFailureReason.SOURCE_LIST_NOT_HERE)))
                        ? null
                        : RefusalCode.TRY_SENDING_NOT_OFFERED;
            case StepPosition.AwaitingReview waiting ->
                switch (waiting.sending()) {
                    case TURNED_AWAY -> null;
                    case TOO_LONG, LIST_NOT_HERE, TAKES_NO_LONGER_DECLARED, NO_LONGER_DECLARED, HOLDS_A_FILE ->
                        RefusalCode.TRY_SENDING_NOT_OFFERED;
                    case UNSENT, OUT -> RefusalCode.STEP_MOVED_ON;
                    case null -> RefusalCode.STEP_MOVED_ON;
                };
            case StepPosition.NotStarted ignored -> RefusalCode.STEP_MOVED_ON;
            case StepPosition.Running ignored -> RefusalCode.STEP_MOVED_ON;
            case StepPosition.Owed ignored -> RefusalCode.STEP_MOVED_ON;
            case StepPosition.Done ignored -> RefusalCode.STEP_MOVED_ON;
            case StepPosition.Beneath ignored -> RefusalCode.STEP_MOVED_ON;
        };
    }

    private static boolean reviewing(StepPosition position) {
        return position instanceof StepPosition.AwaitingReview
                || (position instanceof StepPosition.Failed failed
                        && failed.why() instanceof StepFailure.Recorded recorded
                        && recorded.purpose() == ModelCallPurpose.REVIEW);
    }
}
