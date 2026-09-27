package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.workflow.StepProducer;

/**
 * One try of a step, as the store holds it: open until its production is in or lost.
 *
 * @param number which of the step's tries it is, counted from one; tries are ordered by this and never by time
 * @param producer who produces it, which is the step's producer or a person answering in its place
 * @param askedBy the person who asked for it; none where the system did
 * @param endedAt none while it is open
 * @param endedBy the person who produced it; none where the system ended it, or while it is open
 * @param explanation why the person producing it said it, exactly where a person did
 * @param lost how it ended without giving anything back; none where it gave something back or is open
 * @param fault why this system ended a try of code as gone wrong, exactly where code went wrong without its own words
 * @param lostDetail what code said went wrong where it threw, cut to its bound where {@code lostDetailCut}
 * @param returned what code gave back that did not fit what it declares, as it came back
 * @param values what it gave back, one per field its step's declaration gives back; none unless it did
 * @param reviews every review of it, a decision on each value it decided
 * @param inputs what it took, one per binding of its step
 * @param attempts every attempt to send it to a model, to produce it or to review it, in the order made
 * @param calls every call an attempt of it sent, in the order made
 */
public record TryRecord(
        ProductionId id,
        int number,
        StepProducer producer,
        @Nullable SubjectId askedBy,
        Instant askedAt,
        @Nullable Instant endedAt,
        @Nullable SubjectId endedBy,
        @DoNotLog @Nullable String explanation,
        @Nullable TryLostReason lost,
        CodeError.@Nullable Fault fault,
        @DoNotLog @Nullable String lostDetail,
        boolean lostDetailCut,
        @DoNotLog @Nullable String returned,
        List<ValueRecord> values,
        List<ReviewRecord> reviews,
        List<InputRecord> inputs,
        List<AttemptRecord> attempts,
        List<CallRecord> calls) {

    public TryRecord {
        requireNonNull(id, "TryRecord id must not be null");
        requireNonNull(producer, "TryRecord producer must not be null");
        requireNonNull(askedAt, "TryRecord askedAt must not be null");
        values = List.copyOf(requireNonNull(values, "TryRecord values must not be null"));
        reviews = List.copyOf(requireNonNull(reviews, "TryRecord reviews must not be null"));
        inputs = List.copyOf(requireNonNull(inputs, "TryRecord inputs must not be null"));
        attempts = List.copyOf(requireNonNull(attempts, "TryRecord attempts must not be null"));
        calls = List.copyOf(requireNonNull(calls, "TryRecord calls must not be null"));
        requireSentBy(producer, endedAt != null && lost == null, attempts, calls);
        if (number < 1) {
            throw new IllegalArgumentException("TryRecord number must be positive: " + number);
        }
        if (endedAt == null && (lost != null || endedBy != null || !values.isEmpty())) {
            throw new IllegalArgumentException("TryRecord open ended nowhere and gave nothing back");
        }
        if (lost != null && !values.isEmpty()) {
            throw new IllegalArgumentException("TryRecord lost gave nothing back");
        }
        if ((fault != null) != (producer == StepProducer.CODE && lost == TryLostReason.ERRORED && lostDetail == null)) {
            throw new IllegalArgumentException(
                    "TryRecord gives this system's reason exactly where code went wrong without saying why itself");
        }
    }

    public boolean open() {
        return endedAt == null;
    }

    public boolean yielded() {
        return endedAt != null && lost == null;
    }

    /** A model's try asked and not yet sent: open, with no attempt to produce it written. */
    public boolean unsent() {
        if (endedAt != null || producer != StepProducer.MODEL) {
            return false;
        }
        for (AttemptRecord attempt : attempts) {
            if (attempt.purpose() == ModelCallPurpose.PRODUCE) {
                return false;
            }
        }
        return true;
    }

    /** The review deciding the values waiting on one, which there is at most one of. */
    public Optional<ReviewRecord> onReview() {
        return reviews.stream().filter(review -> !review.forLength()).findFirst();
    }

    private static void requireSentBy(
            StepProducer producer, boolean yielded, List<AttemptRecord> attempts, List<CallRecord> calls) {
        for (AttemptRecord attempt : attempts) {
            if (attempt.purpose() == ModelCallPurpose.PRODUCE && producer != StepProducer.MODEL) {
                throw new IllegalArgumentException("TryRecord is sent to be produced only where a model produces it");
            }
            if (attempt.purpose() == ModelCallPurpose.REVIEW && !yielded) {
                throw new IllegalArgumentException("TryRecord is sent to be reviewed only once it gave something back");
            }
        }
        for (CallRecord call : calls) {
            boolean sent = false;
            for (AttemptRecord attempt : attempts) {
                sent |= attempt.id().equals(call.attempt());
            }
            if (!sent) {
                throw new IllegalArgumentException("TryRecord holds a call no attempt of it sent");
            }
        }
    }
}
