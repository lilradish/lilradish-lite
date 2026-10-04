package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.workflow.Pointer;

/**
 * A failure written down on a step.
 *
 * @param onTry the try the step was on when it failed; none where it was on no try, and then it never ends
 * @param purpose what the step was sending that try for, to be produced or to be reviewed; exactly where it was on one
 * @param answered whether an attempt has since answered it, which ends it
 * @param term the value no case claimed, where that is why it failed and a value was given; none otherwise
 */
public record FailureRecord(
        RunStepFailureId id,
        RunStepFailureReason reason,
        @Nullable ProductionId onTry,
        @Nullable ModelCallPurpose purpose,
        Instant at,
        boolean answered,
        @DoNotLog @Nullable String term,
        @Nullable String tool,
        @Nullable SourceRecord source) {

    public FailureRecord {
        requireNonNull(id, "FailureRecord id must not be null");
        requireNonNull(reason, "FailureRecord reason must not be null");
        requireNonNull(at, "FailureRecord at must not be null");
        if (term != null && reason != RunStepFailureReason.UNCLAIMED_VALUE) {
            throw new IllegalArgumentException("FailureRecord names a term only where no case claimed it");
        }
        if ((reason == RunStepFailureReason.TOOL_NOT_OFFERED) != (tool != null)) {
            throw new IllegalArgumentException(
                    "FailureRecord names a tool exactly where the model no longer offers it");
        }
        if ((onTry == null) != (purpose == null)) {
            throw new IllegalArgumentException(
                    "FailureRecord names what it was sending for exactly where it was on a try");
        }
        if (purpose == ModelCallPurpose.HELP) {
            throw new IllegalArgumentException("FailureRecord was sending a try to be produced or to be reviewed");
        }
        if ((reason == RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
                        || reason == RunStepFailureReason.SOURCE_LIST_NOT_HERE)
                != (source != null)) {
            throw new IllegalArgumentException("FailureRecord names a source exactly where it no longer matches");
        }
        if (source != null && purpose != ModelCallPurpose.PRODUCE) {
            throw new IllegalArgumentException("FailureRecord names a source only on a model production to be sent");
        }
    }

    /** The actual code maker and the field read from it, using the declaration's pointer spelling. */
    public record SourceRecord(HoldRecord.SourceRecord maker, String field) {

        public SourceRecord {
            requireNonNull(maker, "FailureRecord.SourceRecord maker must not be null");
            field = Pointer.parse(requireNonNull(field, "FailureRecord.SourceRecord field must not be null"))
                    .published();
        }
    }
}
