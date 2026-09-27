package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.workflow.Producer;
import org.lilradish.lite.domain.workflow.StepProducer;

/**
 * Where a step stands as far as what somebody may do to it turns on.
 *
 * @param runStopped whether a stop is in force on its run's tree
 * @param entryStopped whether what it runs, or its workflow, is stopped
 * @param producer who its step names to produce; none where what it runs produces nothing of its own
 * @param readerProduced whether the one asking produced the values waiting on a review
 * @param codeMayRunAgain whether it runs a code step the running release holds and lets run again
 * @param codeGivesOtherwise where it runs a code step the running release holds, owing a try a person may make, what
 *     a person would give back as the release declares it no longer matches in what the version reads of it, and
 *     what reads it, or the field of it pinning a list this group does not hold; none where it matches, or where no
 *     such try is owed
 * @param unreleased whether it runs a code step the running release does not hold, of which nothing is sent
 */
public record StepGround(
        StepPosition position,
        boolean runStopped,
        boolean entryStopped,
        @Nullable StepProducer producer,
        boolean readerProduced,
        boolean codeMayRunAgain,
        CodeError.@Nullable Fault codeGivesOtherwise,
        boolean unreleased) {

    public StepGround {
        requireNonNull(position, "StepGround position must not be null");
    }

    /** Where {@code step}, at {@code position}, stands to {@code reader}; none as a reader is nobody's producer. */
    public static StepGround of(RunSnapshot run, StepSnapshot step, StepPosition position, @Nullable SubjectId reader) {
        requireNonNull(run, "StepGround run must not be null");
        requireNonNull(step, "StepGround step must not be null");
        requireNonNull(position, "StepGround position must not be null");
        Producer producer = step.planned().producer();
        ReleasedCodeStep released = step.planned().runs() instanceof StepRuns.Code code ? code.released() : null;
        return new StepGround(
                position,
                run.stopped(),
                run.stoppedFor(step) != null,
                producer == null ? null : producer.kind(),
                position instanceof StepPosition.AwaitingReview waiting
                        && waiting.producedBy() != null
                        && waiting.producedBy().equals(reader),
                released != null && released.mayRunAgain(),
                released != null && position.nextTry() != null
                        ? CodeStepFit.givesOtherwise(run, step).orElse(null)
                        : null,
                EngineActs.unreleased(step.planned()));
    }
}
