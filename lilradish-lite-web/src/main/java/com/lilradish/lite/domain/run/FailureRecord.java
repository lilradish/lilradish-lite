package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.inference.ModelCallPurpose;
import com.lilradish.lite.domain.workflow.Pointer;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * @param onTry the try the step was sending; none where it failed before a try could be sent
 * @param purpose what the step was sending that try for, to be produced or to be reviewed; exactly where it was on one
 * @param ended whether an attempt answered it or its search question was revalidated
 * @param term the value no case claimed, where that is why it failed and a value was given; none otherwise
 */
public record FailureRecord(
        RunStepFailureId id,
        RunStepFailureReason reason,
        @Nullable ProductionId onTry,
        @Nullable ModelCallPurpose purpose,
        Instant at,
        boolean ended,
        @DoNotLog @Nullable String term,
        @Nullable String tool,
        @Nullable SourceRecord source) {

    public FailureRecord {
        requireNonNull(id, "FailureRecord id must not be null");
        requireNonNull(reason, "FailureRecord reason must not be null");
        requireNonNull(at, "FailureRecord at must not be null");
        if (reason == RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES && onTry != null) {
            throw new IllegalArgumentException("A search question mismatch spends no try");
        }
        if (ended && onTry == null && reason != RunStepFailureReason.SEARCH_QUESTION_NO_LONGER_MATCHES) {
            throw new IllegalArgumentException("Only a search question mismatch can end without a sending try");
        }
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

    /** Using the declaration's pointer spelling. */
    public record SourceRecord(HoldRecord.SourceRecord maker, String field) {

        public SourceRecord {
            requireNonNull(maker, "FailureRecord.SourceRecord maker must not be null");
            field = Pointer.parse(requireNonNull(field, "FailureRecord.SourceRecord field must not be null"))
                    .published();
        }
    }
}
