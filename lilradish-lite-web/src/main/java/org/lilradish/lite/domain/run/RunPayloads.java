package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.lilradish.lite.domain.search.SearchAnswer;
import org.lilradish.lite.domain.search.SearchWay;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.workflow.Producer;

/**
 * A model reviewing receives no production identity or confidence; code is described by the running release.
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

    public static JsonObject toProduce(
            RunInTree at, PlannedStep step, List<BindingRecord> bound, @Nullable TryRecord refused) {
        requireNonNull(at, "RunPayloads context must not be null");
        StepRuns.Question question = question(step);
        Asking asking = Asking.of(question.instruction(), question.takes(), question.gives(), question.lists());
        return Payload.toProduce(
                asking,
                taken(question.takes(), bound),
                told(refused, tells(step)),
                fromTheWeb(at, bound, question.takes()));
    }

    /** A historical production is reviewed against the running release, not its original declaration. */
    public static ReviewPayload toReview(RunInTree at, StepSnapshot step, TryRecord reviewed) {
        return toReview(at, step, reviewed, null, null);
    }

    public static ReviewPayload toReview(
            RunInTree at,
            StepSnapshot step,
            TryRecord reviewed,
            @Nullable String searchQuestion,
            JsonValue.@Nullable JsonObject searchRecord) {
        return toReview(at, step, reviewed, searchQuestion, searchRecord, null);
    }

    public static ReviewPayload toReview(
            RunInTree at,
            StepSnapshot step,
            TryRecord reviewed,
            @Nullable String searchQuestion,
            JsonValue.@Nullable JsonObject searchRecord,
            JsonValue.@Nullable JsonObject questionSent) {
        requireNonNull(at, "RunPayloads context must not be null");
        requireNonNull(step, "RunPayloads step must not be null");
        requireNonNull(reviewed, "RunPayloads reviewed must not be null");
        RunSnapshot run = at.run();
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
            case StepRuns.Question question -> {
                if (questionSent != null) {
                    Optional<JsonObject> kept = Payload.toReviewKept(
                            Asking.of(question.instruction(), question.takes(), question.gives(), question.lists()),
                            questionSent,
                            told,
                            given(reviewed),
                            deciding);
                    yield kept.<ReviewPayload>map(ReviewPayload.Built::new)
                            .orElseGet(() -> new ReviewPayload.Unbuilt(ReviewUnbuiltReason.NO_LONGER_DECLARED));
                }
                List<BindingRecord> bound = StepInputs.took(at, step, reviewed);
                yield new ReviewPayload.Built(Payload.toReview(
                        Asking.of(question.instruction(), question.takes(), question.gives(), question.lists()),
                        taken(question.takes(), bound),
                        told,
                        given(reviewed),
                        deciding,
                        fromTheWeb(at, bound, question.takes())));
            }
            case StepRuns.Code code -> reviewedCode(at, step, code, told, reviewed, deciding);
            case StepRuns.Search search -> {
                SearchWay way = search.released();
                if (way == null) {
                    yield new ReviewPayload.Unbuilt(ReviewUnbuiltReason.NO_LONGER_DECLARED);
                }
                StepInputs.tookSearch(at, search.question(), reviewed);
                List<AskedField> gives = SearchAnswer.asked(way);
                if (!Payload.holds(gives, given(reviewed)) || (told != null && !Payload.holds(gives, told.values()))) {
                    yield new ReviewPayload.Unbuilt(ReviewUnbuiltReason.NO_LONGER_DECLARED);
                }
                yield new ReviewPayload.Built(Payload.toReviewSearch(
                        requireNonNull(searchQuestion, "A search review keeps its historical question"),
                        gives,
                        told,
                        given(reviewed),
                        deciding,
                        requireNonNull(searchRecord, "A search review keeps its historical records")));
            }
            case StepRuns.Workflow ignored -> throw notProduced();
            case StepRuns.Route ignored -> throw notProduced();
        };
    }

    private static ReviewPayload reviewedCode(
            RunInTree at,
            StepSnapshot step,
            StepRuns.Code code,
            Payload.@Nullable Refused told,
            TryRecord reviewed,
            List<FieldName> deciding) {
        RunSnapshot run = at.run();
        ReleasedCodeStep released = code.released();
        if (released == null) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.NO_LONGER_DECLARED);
        }
        Declaration takes = released.declared().takes();
        Declaration givesDeclared = released.declared().gives();
        if (takes.hasFile() || givesDeclared.hasFile()) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.HOLDS_A_FILE);
        }
        if (!released.listsMissing().isEmpty()) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.LIST_NOT_HERE);
        }
        // Judged as it is before its code runs: what the version binds into it against what the release takes now.
        CodeError.Fault misfit = CodeStepFit.misfit(run, step).orElse(null);
        if (misfit != null && misfit.reason() == CodeErrorReason.TAKES_OTHERWISE) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED);
        }
        List<AskedField> gives = Asking.told(givesDeclared, released.lists());
        if (!Payload.holds(gives, given(reviewed)) || (told != null && !Payload.holds(gives, told.values()))) {
            return new ReviewPayload.Unbuilt(ReviewUnbuiltReason.NO_LONGER_DECLARED);
        }
        List<BindingRecord> bound = StepInputs.took(at, step, reviewed);
        return new ReviewPayload.Built(Payload.toReviewUninstructed(
                Asking.told(takes, released.lists()),
                gives,
                taken(takes, bound),
                told,
                given(reviewed),
                deciding,
                fromTheWeb(at, bound, takes)));
    }

    private static List<FieldName> fromTheWeb(RunInTree at, List<BindingRecord> bound, Declaration takes) {
        Set<ProductionValueId> wanted = new HashSet<>();
        for (BindingRecord input : bound) {
            wanted.addAll(input.sources());
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        RunInTree root = at;
        while (root.above() != null) {
            root = requireNonNull(root.above());
        }
        Set<ProductionValueId> searched = new HashSet<>();
        ArrayDeque<RunSnapshot> pending = new ArrayDeque<>();
        pending.push(root.run());
        while (!pending.isEmpty() && !wanted.isEmpty()) {
            RunSnapshot run = pending.pop();
            for (StepSnapshot step : run.steps()) {
                for (TryRecord aTry : step.tries()) {
                    for (ValueRecord value : aTry.values()) {
                        if (wanted.remove(value.id()) && step.planned().runs() instanceof StepRuns.Search) {
                            searched.add(value.id());
                        }
                    }
                }
                if (step.beneath() != null) {
                    pending.push(step.beneath().run());
                }
            }
        }
        if (!wanted.isEmpty()) {
            throw new IllegalStateException("An input's historical maker is missing from its tree");
        }
        Set<FieldName> marked = new HashSet<>();
        for (BindingRecord input : bound) {
            if (input.sources().stream().anyMatch(searched::contains)) {
                marked.add(requireNonNull(input.binding().target(), "a model input fills a field")
                        .names()
                        .getFirst());
            }
        }
        return takes.fields().stream()
                .map(field -> field.name())
                .filter(marked::contains)
                .toList();
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
            case MODEL -> tells(step.planned()) ? refusedInWords(step, reviewed.id()) : Optional.empty();
            case PERSON -> refusedInWords(step, reviewed.id());
            case CODE -> Optional.empty();
        };
    }

    /** The newest production refused in words before the actual production named, whether it has a number or not. */
    public static Optional<TryRecord> refusedInWords(StepSnapshot step, ProductionId asking) {
        requireNonNull(step, "RunPayloads step must not be null");
        requireNonNull(asking, "RunPayloads asking must not be null");
        List<TryRecord> tries = step.tries();
        for (int index = 0; index < tries.size(); index++) {
            if (tries.get(index).id().equals(asking)) {
                return refusedBefore(tries, index);
            }
        }
        throw new IllegalArgumentException("RunPayloads names a production its step does not hold");
    }

    /** The newest production refused in words before the next answer, which may not have been asked yet. */
    public static Optional<TryRecord> refusedBeforeAnswer(StepSnapshot step) {
        requireNonNull(step, "RunPayloads step must not be null");
        List<TryRecord> tries = step.tries();
        int before = tries.size() - (step.newest().map(TryRecord::open).orElse(false) ? 1 : 0);
        return refusedBefore(tries, before);
    }

    private static Optional<TryRecord> refusedBefore(List<TryRecord> tries, int before) {
        for (int index = before - 1; index >= 0; index--) {
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

    public static Payload.@Nullable Refused told(@Nullable TryRecord refused, boolean tells) {
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
