package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.codestep.CodeErrorReason;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.inference.Payload;
import org.lilradish.lite.domain.run.StepInputs.BindingRecord;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.workflow.Producer;

/**
 * What a model is sent beside the envelope, worked out from a run as the store holds it: every field the step
 * takes, as {@link StepInputs#filled} fills it; and a production refused before where the step tells the next
 * asking what happened, which {@link #refusedInWords} names. A model reviewing is sent what went into the
 * production under review and everything that came out of it, and never who or what produced it.
 */
public final class RunPayloads {

    private RunPayloads() {}

    /**
     * @param bound each binding's value as this try takes it, each filling one place the step takes
     * @param refused the production refused before that this asking follows, none where there is none; told only
     *     where the step's model is told what happened, and only as far as it was refused in words
     */
    public static JsonObject toProduce(PlannedStep step, List<BindingRecord> bound, @Nullable TryRecord refused) {
        requireNonNull(step, "RunPayloads step must not be null");
        StepRuns.Question question = question(step);
        Asking asking = Asking.of(question.instruction(), question.takes(), question.gives(), question.lists());
        return Payload.toProduce(asking, taken(question.takes(), bound), told(refused, tells(step)));
    }

    /**
     * What the model {@code step} names is sent to review {@code reviewed}: what went into it, as
     * {@link StepInputs#took} reads it back; the production refused before that went into it, as {@link #wentInto}
     * names it, told only as far as it was refused in words; every value it gave back, none as none, and no
     * confidence; and those {@link #deciding} names, to decide. A code step's production is reviewed as the running
     * release declares that code step now, with no instruction, and cannot be built where the release pins a list
     * not here, no longer takes what the version binds into it, or no longer declares what came back of the try or of
     * the production told; the first of those found, in that order, is said instead.
     */
    public static ReviewPayload toReview(RunSnapshot run, StepSnapshot step, TryRecord reviewed) {
        requireNonNull(run, "RunPayloads run must not be null");
        requireNonNull(step, "RunPayloads step must not be null");
        requireNonNull(reviewed, "RunPayloads reviewed must not be null");
        if (!reviewed.yielded()) {
            throw new IllegalArgumentException("RunPayloads reviews only a production that gave something back");
        }
        List<ValueRecord> waiting = deciding(reviewed);
        List<FieldName> deciding = new ArrayList<>(waiting.size());
        for (ValueRecord value : waiting) {
            deciding.add(new FieldName(value.field()));
        }
        Payload.Refused told = told(wentInto(step, reviewed).orElse(null), true);
        return switch (step.planned().runs()) {
            case StepRuns.Question question ->
                new ReviewPayload.Built(Payload.toReview(
                        Asking.of(question.instruction(), question.takes(), question.gives(), question.lists()),
                        taken(question.takes(), StepInputs.took(run, step, reviewed)),
                        told,
                        given(reviewed),
                        deciding));
            case StepRuns.Code code -> reviewedCode(run, step, code, told, reviewed, deciding);
            case StepRuns.Workflow ignored -> throw notProduced();
            case StepRuns.Route ignored -> throw notProduced();
        };
    }

    private static ReviewPayload reviewedCode(
            RunSnapshot run,
            StepSnapshot step,
            StepRuns.Code code,
            Payload.@Nullable Refused told,
            TryRecord reviewed,
            List<FieldName> deciding) {
        ReleasedCodeStep released = requireNonNull(code.released(), "a code step reviewed is one the release holds");
        if (!released.listsMissing().isEmpty()) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.LIST_NOT_HERE);
        }
        // Judged as it is before its code runs: what the version binds into it against what the release takes now.
        CodeError.Fault misfit = CodeStepFit.misfit(run, step).orElse(null);
        if (misfit != null && misfit.reason() == CodeErrorReason.TAKES_OTHERWISE) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED);
        }
        List<AskedField> gives = Asking.told(released.declared().gives(), released.lists());
        if (!Payload.holds(gives, given(reviewed)) || (told != null && !Payload.holds(gives, told.values()))) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.NO_LONGER_DECLARED);
        }
        Declaration takes = released.declared().takes();
        return new ReviewPayload.Built(Payload.toReviewUninstructed(
                Asking.told(takes, released.lists()),
                gives,
                taken(takes, StepInputs.took(run, step, reviewed)),
                told,
                given(reviewed),
                deciding));
    }

    private static IllegalArgumentException notProduced() {
        return new IllegalArgumentException("RunPayloads reviews only what a question or a code step produced");
    }

    /** Every value of {@code reviewed} waiting on a review, in the order its step's declaration gives them back. */
    public static List<ValueRecord> deciding(TryRecord reviewed) {
        requireNonNull(reviewed, "RunPayloads reviewed must not be null");
        List<ValueRecord> waiting = new ArrayList<>(reviewed.values().size());
        for (ValueRecord value : reviewed.values()) {
            if (ValueStanding.of(reviewed, value) == ValueStanding.WAITING_ON_REVIEW) {
                waiting.add(value);
            }
        }
        return List.copyOf(waiting);
    }

    /**
     * The production refused before {@code reviewed} of {@code step} that went into it, none where none did: what
     * the step told its model where a model produced it, and what answering it here showed where a person did. Code
     * is told nothing.
     */
    public static Optional<TryRecord> wentInto(StepSnapshot step, TryRecord reviewed) {
        requireNonNull(step, "RunPayloads step must not be null");
        requireNonNull(reviewed, "RunPayloads reviewed must not be null");
        return switch (reviewed.producer()) {
            case MODEL -> tells(step.planned()) ? refusedInWords(step, reviewed.number()) : Optional.empty();
            case PERSON -> refusedInWords(step, reviewed.number());
            case CODE -> Optional.empty();
        };
    }

    /**
     * The production refused before try {@code asking} of {@code step}, the one wherever it is named: the newest try
     * numbered below {@code asking} whose production a review refused in words, for its values or for their length;
     * none where none was. Tries lost, open, or refused in no words, as by a review that went wrong, are passed over.
     */
    public static Optional<TryRecord> refusedInWords(StepSnapshot step, int asking) {
        requireNonNull(step, "RunPayloads step must not be null");
        if (asking < 1) {
            throw new IllegalArgumentException("RunPayloads asking must be a try's number: " + asking);
        }
        List<TryRecord> tries = step.tries();
        for (int index = Math.min(asking - 1, tries.size()) - 1; index >= 0; index--) {
            TryRecord aTry = tries.get(index);
            if (aTry.yielded() && refusedInWords(aTry)) {
                return Optional.of(aTry);
            }
        }
        return Optional.empty();
    }

    private static StepRuns.Question question(PlannedStep step) {
        if (!(step.runs() instanceof StepRuns.Question question)) {
            throw new IllegalArgumentException("RunPayloads asks only a step running a question");
        }
        return question;
    }

    private static boolean tells(PlannedStep step) {
        return step.producer() instanceof Producer.Model model && model.toldWhatHappened();
    }

    private static Map<FieldName, JsonValue> taken(Declaration takes, List<BindingRecord> bound) {
        JsonObject filled = StepInputs.filled(takes, bound);
        Map<FieldName, JsonValue> taken = HashMap.newHashMap(filled.members().size());
        for (JsonMember member : filled.members()) {
            taken.put(new FieldName(member.name()), member.value());
        }
        return taken;
    }

    private static Payload.@Nullable Refused told(@Nullable TryRecord refused, boolean tells) {
        if (refused == null) {
            return null;
        }
        if (!refused.yielded()) {
            throw new IllegalArgumentException("RunPayloads tells only a production that gave something back");
        }
        if (!anyRefused(refused)) {
            throw new IllegalArgumentException("RunPayloads tells only a production with a value refused");
        }
        if (!tells) {
            return null;
        }
        Map<FieldName, String> words = HashMap.newHashMap(refused.values().size());
        for (ValueRecord value : refused.values()) {
            String why = why(refused, value);
            if (why != null) {
                words.put(new FieldName(value.field()), why);
            }
        }
        // Only a review that went wrong refuses in no words, and of it there is nothing to tell.
        return words.isEmpty() ? null : new Payload.Refused(given(refused), words);
    }

    /** The words a review refused this value with. */
    private static @Nullable String why(TryRecord aTry, ValueRecord value) {
        for (ReviewRecord review : aTry.reviews()) {
            DecisionRecord decision = review.decisionOn(value.id()).orElse(null);
            if (decision != null && decision.outcome() == ReviewOutcome.REFUSED) {
                return decision.why();
            }
        }
        return null;
    }

    private static Map<FieldName, JsonValue> given(TryRecord aTry) {
        Map<FieldName, JsonValue> given = HashMap.newHashMap(aTry.values().size());
        for (ValueRecord value : aTry.values()) {
            given.put(new FieldName(value.field()), value.value());
        }
        return given;
    }

    private static boolean anyRefused(TryRecord aTry) {
        for (ValueRecord value : aTry.values()) {
            if (refused(aTry, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean refusedInWords(TryRecord aTry) {
        for (ValueRecord value : aTry.values()) {
            if (refused(aTry, value) && why(aTry, value) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean refused(TryRecord aTry, ValueRecord value) {
        ValueStanding standing = ValueStanding.of(aTry, value);
        return standing == ValueStanding.REFUSED || standing == ValueStanding.REFUSED_FOR_LENGTH;
    }
}
