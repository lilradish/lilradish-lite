package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.codestep.CodeError;
import com.lilradish.lite.domain.identity.SubjectId;
import com.lilradish.lite.domain.inference.DidNotFitReason;
import com.lilradish.lite.domain.run.DecisionRecord;
import com.lilradish.lite.domain.run.PlannedStep;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.ProductionValueId;
import com.lilradish.lite.domain.run.ReviewRecord;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunStepSendAttemptId;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryLostReason;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.ValueRecord;
import com.lilradish.lite.domain.run.ValueStanding;
import com.lilradish.lite.domain.workflow.Pointer;
import com.lilradish.lite.domain.workflow.Producer;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * Each try of a step as its reader is answered, and what a step's tries spent, read through the reading it is part
 * of. What was read of calls is supplied here, some of it after this is made by the reading's {@code with} calls.
 */
final class StepTries {

    private static final StepAnswers.CostAnswer NO_CALL =
            new StepAnswers.CostAnswer(false, null, null, null, null, null);

    private final StepReading stepReading;

    private final StepGivenBack stepGivenBack;

    private final Map<ProductionId, RunBudget.Spend> spent;

    private final Map<ProductionId, LostRecord> lost;

    private final Map<ProductionId, List<TurnawayRecord>> turnedAway;

    private Map<ProductionId, RawFactsRecord> rawFacts = Map.of();

    private Map<ProductionId, List<StepAnswers.KeptCodeFileAnswer>> keptFiles = Map.of();

    private Map<ProductionId, StepAnswers.SearchCountAnswer> searches = Map.of();

    private Map<RunId, RunBudget.Spend> beneathCost = Map.of();

    StepTries(
            StepReading stepReading,
            StepGivenBack stepGivenBack,
            Map<ProductionId, RunBudget.Spend> spent,
            Map<ProductionId, LostRecord> lost,
            Map<ProductionId, List<TurnawayRecord>> turnedAway) {
        this.stepReading = stepReading;
        this.stepGivenBack = stepGivenBack;
        this.spent = spent;
        this.lost = lost;
        this.turnedAway = turnedAway;
    }

    void withSearch(Map<ProductionId, StepAnswers.SearchCountAnswer> searches) {
        this.searches = Map.copyOf(searches);
    }

    void withRawFacts(Map<ProductionId, RawFactsRecord> facts) {
        this.rawFacts = Map.copyOf(facts);
    }

    record RawFactsRecord(
            boolean producedDeclined,
            boolean producedUnreadable,
            boolean producedReply,
            boolean reviewedDeclined,
            boolean reviewedUnreadable,
            boolean reviewedReply) {}

    void withKeptFiles(Map<ProductionId, List<StepAnswers.KeptCodeFileAnswer>> keptFiles) {
        this.keptFiles = Map.copyOf(keptFiles);
    }

    void withBeneathCost(Map<RunId, RunBudget.Spend> beneathCost) {
        this.beneathCost = beneathCost;
    }

    StepAnswers.CostAnswer costBeneath(StepSnapshot step) {
        RunBudget.Spend cost =
                beneathCost.get(requireNonNull(step.beneath()).run().run());
        if (cost == null) {
            throw new IllegalStateException("A child run has no cost in this read");
        }
        return cost.totalCalls() == 0 ? NO_CALL : spend(cost);
    }

    StepAnswers.CostAnswer cost(PlannedStep planned, List<TryRecord> tries) {
        if (!callsAModel(planned)) {
            return NO_CALL;
        }
        RunBudget.Spend total = RunBudget.Spend.NOTHING;
        for (TryRecord aTry : tries) {
            total = total.and(spent.getOrDefault(aTry.id(), RunBudget.Spend.NOTHING));
        }
        return spend(total);
    }

    private static boolean callsAModel(PlannedStep planned) {
        return planned.producer() instanceof Producer.Model || planned.reviewer() != null;
    }

    private static StepAnswers.CostAnswer spend(RunBudget.Spend spend) {
        return new StepAnswers.CostAnswer(
                true,
                Long.toString(spend.sent()),
                Long.toString(spend.cameBack()),
                Long.toString(spend.spent()),
                spend.cameBackUnknown(),
                spend.measuredHere() == null ? null : spend.measuredHere().published());
    }

    StepAnswers.TryAnswer tryAnswer(
            StepSnapshot step, TryRecord aTry, Map<ProductionValueId, RunActs.Held> heldForLength) {
        PlannedStep planned = step.planned();
        StepGivenBack.ValueReading reading = stepGivenBack.reading(planned);
        ReviewRecord review = aTry.onReview().orElse(null);
        LostRecord why = lost.get(aTry.id());
        DidNotFitReason misfit = why == null ? null : why.didNotFit();
        RawFactsRecord facts =
                rawFacts.getOrDefault(aTry.id(), new RawFactsRecord(false, false, false, false, false, false));
        return new StepAnswers.TryAnswer(
                requireNonNull(aTry.number()),
                requireNonNull(aTry.number()) > planned.declaredTries(),
                stepReading.person(aTry.askedBy()),
                producedBy(planned, aTry),
                aTry.explanation(),
                reading == null ? List.of() : triedValues(aTry, reading, heldForLength),
                new StepAnswers.ReviewAnswer(
                        aTry.values().stream().anyMatch(ValueRecord::needsReview),
                        review == null ? null : reviewedBy(planned, review),
                        review != null && review.lost() != null ? Boolean.TRUE : null,
                        review == null || review.lost() == null
                                ? null
                                : review.lost().published(),
                        review == null || review.misfit() == null
                                ? null
                                : review.misfit().published(),
                        stepReading.readsModels && facts.reviewedDeclined(),
                        stepReading.readsModels && facts.reviewedUnreadable(),
                        stepReading.readsModels && facts.reviewedReply()),
                ended(aTry).published(),
                misfit == null ? null : misfit.published(),
                wentWrong(why),
                aTry.lostDetailPresent(),
                erroredFor(aTry),
                aTry.returnedPresent(),
                keptFiles.get(aTry.id()),
                turnaways(aTry),
                callsAModel(planned) ? spend(spent.getOrDefault(aTry.id(), RunBudget.Spend.NOTHING)) : NO_CALL,
                planned.runs() instanceof StepRuns.Search && aTry.producer() == StepProducer.MODEL
                        ? searches.get(aTry.id())
                        : null,
                stepReading.readsModels && facts.producedDeclined(),
                stepReading.readsModels && facts.producedUnreadable(),
                stepReading.readsModels && facts.producedReply());
    }

    /*
     * Where the reason is found in what came back, the code gave back something, so none kept is something lost; for
     * any other reason nothing came back to keep, which is not the same absence.
     */
    private static StepAnswers.@Nullable ErroredForAnswer erroredFor(TryRecord aTry) {
        CodeError.Fault fault = aTry.fault();
        if (fault == null) {
            return null;
        }
        CodeError.UndeclaredMember member = fault.member();
        CodeError.ReadBy readBy = fault.readBy();
        return new StepAnswers.ErroredForAnswer(
                fault.reason().published(),
                fault.path().isEmpty() ? null : new Pointer(fault.path()).published(),
                member == null ? null : member.name(),
                member != null && member.cut() ? Boolean.TRUE : null,
                readBy == null ? null : StepAnswers.ReadByAnswer.of(readBy),
                fault.reason().aboutWhatCameBack() && !aTry.returnedPresent() ? Boolean.TRUE : null);
    }

    /** A model's call failure, withheld where models are not read; code's own account is read separately. */
    private StepAnswers.@Nullable WentWrongAnswer wentWrong(@Nullable LostRecord why) {
        String said = why == null ? null : why.wentWrong();
        if (said == null) {
            return null;
        }
        if (why != null && why.unreadableResponsePresent()) {
            return new StepAnswers.WentWrongAnswer(
                    "The model service returned a response this system could not read.", false, null);
        }
        return stepReading.readsModels
                ? new StepAnswers.WentWrongAnswer(said, requireNonNull(why).wentWrongCut(), null)
                : new StepAnswers.WentWrongAnswer(null, null, Boolean.TRUE);
    }

    @Nullable
    List<StepAnswers.TurnawayAnswer> turnaways(TryRecord aTry) {
        List<TurnawayRecord> turned = turnedAway.get(aTry.id());
        return turned == null ? null : turned.stream().map(this::turnaway).toList();
    }

    private StepAnswers.TurnawayAnswer turnaway(TurnawayRecord turnaway) {
        String said = turnaway.said();
        boolean withheld = said != null && !stepReading.readsModels;
        return new StepAnswers.TurnawayAnswer(
                StepReading.time(turnaway.at()),
                withheld ? null : said,
                said == null || withheld ? null : turnaway.saidCut(),
                withheld ? Boolean.TRUE : null,
                turnaway.sentAgain());
    }

    private StepAnswers.WhoAnswer reviewedBy(PlannedStep planned, ReviewRecord review) {
        SubjectId by = review.by();
        if (by != null) {
            return new StepAnswers.WhoAnswer("person", stepReading.person(by), null, null);
        }
        return StepProducersAndReviewers.model(
                requireNonNull(planned.reviewer(), "only the model a step names reviews in its stead"));
    }

    private StepAnswers.WhoAnswer producedBy(PlannedStep planned, TryRecord aTry) {
        return switch (aTry.producer()) {
            case PERSON -> new StepAnswers.WhoAnswer("person", stepReading.person(aTry.endedBy()), null, null);
            case CODE -> new StepAnswers.WhoAnswer("code", null, null, null);
            case MODEL -> {
                if (!(planned.producer() instanceof Producer.Model model)) {
                    throw new IllegalStateException(
                            "A model produced try " + aTry.id().value() + " of a step naming none");
                }
                yield StepProducersAndReviewers.model(model.choice());
            }
        };
    }

    Map<ProductionValueId, RunActs.Held> heldForLength(StepSnapshot step) {
        StepGivenBack.ValueReading reading = stepGivenBack.reading(step.planned());
        if (reading == null) {
            return Map.of();
        }
        Set<ProductionValueId> lengthValues = new LinkedHashSet<>();
        for (TryRecord aTry : step.tries()) {
            if (aTry.number() == null || aTry.reviews().stream().noneMatch(ReviewRecord::forLength)) {
                continue;
            }
            for (ValueRecord value : reading.values(aTry)) {
                if (aTry.reviews().stream()
                        .anyMatch(review -> review.forLength()
                                && review.decisionOn(value.id()).isPresent())) {
                    lengthValues.add(value.id());
                }
            }
        }
        return lengthValues.isEmpty()
                ? Map.of()
                : requireNonNull(stepReading.details()).heldForLength(List.copyOf(lengthValues));
    }

    List<StepAnswers.TriedValueAnswer> triedValues(
            TryRecord aTry, StepGivenBack.ValueReading reading, Map<ProductionValueId, RunActs.Held> heldForLength) {
        List<ValueRecord> shownValues = reading.values(aTry);
        List<StepAnswers.TriedValueAnswer> values =
                new ArrayList<>(aTry.values().size());
        boolean modelSaid = aTry.producer() == StepProducer.MODEL && !stepReading.readsModels;
        boolean reviewerSaid =
                aTry.onReview().map(review -> review.by() == null).orElse(false) && !stepReading.readsModels;
        for (ValueRecord value : shownValues) {
            ShownValues.Shown shown = reading.shown(value);
            DecisionRecord length = aTry.reviews().stream()
                    .filter(ReviewRecord::forLength)
                    .flatMap(review -> review.decisionOn(value.id()).stream())
                    .findFirst()
                    .orElse(null);
            DecisionRecord decision = length == null
                    ? aTry.onReview()
                            .flatMap(review -> review.decisionOn(value.id()))
                            .orElse(null)
                    : length;
            values.add(new StepAnswers.TriedValueAnswer(
                    value.field(),
                    shown.value(),
                    shown.withheld(),
                    ValueStanding.of(aTry, value).published(),
                    modelSaid ? null : value.confidence(),
                    decision == null
                            ? null
                            : new StepAnswers.DecisionAnswer(
                                    decision.outcome().published(),
                                    reviewerSaid && length == null ? null : decision.why(),
                                    reviewerSaid && length == null && decision.why() != null ? Boolean.TRUE : null,
                                    decision.lengthUnits() == null ? null : Long.toString(decision.lengthUnits()),
                                    decision.excessUnits() == null ? null : Long.toString(decision.excessUnits()),
                                    length == null
                                            ? null
                                            : requireNonNull(
                                                    heldForLength.get(value.id()),
                                                    "A length refusal names its held step")),
                    shown.earlierShape()));
        }
        return values;
    }

    private static TryEnded ended(TryRecord aTry) {
        if (aTry.open()) {
            return TryEnded.OPEN;
        }
        TryLostReason lost = aTry.lost();
        if (lost != null) {
            return TryEnded.of(lost);
        }
        Set<ValueStanding> standings = new LinkedHashSet<>();
        aTry.values().forEach(value -> standings.add(ValueStanding.of(aTry, value)));
        if (standings.contains(ValueStanding.REFUSED)) {
            return TryEnded.REFUSED_ON_REVIEW;
        }
        if (standings.contains(ValueStanding.REFUSED_FOR_LENGTH)) {
            return TryEnded.REFUSED_FOR_LENGTH;
        }
        return standings.contains(ValueStanding.WAITING_ON_REVIEW) ? TryEnded.WAITING : TryEnded.STANDS;
    }

    // Record is a suffix AppLoggingConvention matches: without it, @DoNotLog does nothing.
    /**
     * @param didNotFit why its answer did not fit, where it did not
     * @param wentWrong what its call said went wrong, where it did, cut to its bound where {@code wentWrongCut}
     */
    record LostRecord(
            @Nullable DidNotFitReason didNotFit,
            @DoNotLog @Nullable String wentWrong,
            boolean wentWrongCut,
            boolean unreadableResponsePresent) {}

    /**
     * @param attempt the attempt whose call it was
     * @param said what the model said, where it said anything, cut to its bound where {@code saidCut}
     * @param sentAgain whether the call was sent again after it
     */
    record TurnawayRecord(
            RunStepSendAttemptId attempt,
            Instant at,
            @DoNotLog @Nullable String said,
            boolean saidCut,
            boolean sentAgain) {}
}
