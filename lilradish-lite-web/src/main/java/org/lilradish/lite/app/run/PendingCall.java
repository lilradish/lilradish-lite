package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.inference.CallRequest;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.run.ModelCallId;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.ProductionValueId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;

/**
 * A call to a model producing or reviewing a try, written down as sent with the attempt that lets it through. What
 * it sends renders only as how long it is, as {@link CallRequest} does.
 *
 * @param root the run at the top of {@code run}'s tree, which every write about the call locks
 * @param aTry the try produced, or the one reviewed
 * @param reading what its answer is read against, for the purpose {@code request} names
 */
record PendingCall(
        GroupId group,
        RunId root,
        RunId run,
        ProductionId aTry,
        RunStepSendAttemptId attempt,
        ModelCallId call,
        CallRequest request,
        Reading reading) {

    PendingCall {
        requireNonNull(group, "PendingCall group must not be null");
        requireNonNull(root, "PendingCall root must not be null");
        requireNonNull(run, "PendingCall run must not be null");
        requireNonNull(aTry, "PendingCall try must not be null");
        requireNonNull(attempt, "PendingCall attempt must not be null");
        requireNonNull(call, "PendingCall call must not be null");
        requireNonNull(request, "PendingCall request must not be null");
        requireNonNull(reading, "PendingCall reading must not be null");
        if (reading.purpose() != request.purpose()) {
            throw new IllegalArgumentException(
                    "PendingCall reads an answer to " + request.purpose() + " as one to " + reading.purpose());
        }
    }

    /** What an answer is read against: what the try gives back, or which of its values are decided. */
    sealed interface Reading permits Producing, Reviewing {

        ModelCallPurpose purpose();
    }

    /**
     * @param gives what the try gives back, as the model was told it, which its answer is read against
     * @param givesFields the stored key of each field {@code gives} holds, in the same order
     */
    record Producing(List<AskedField> gives, List<UUID> givesFields) implements Reading {

        Producing {
            gives = List.copyOf(requireNonNull(gives, "PendingCall.Producing gives must not be null"));
            givesFields =
                    List.copyOf(requireNonNull(givesFields, "PendingCall.Producing givesFields must not be null"));
            if (gives.size() != givesFields.size()) {
                throw new IllegalArgumentException("PendingCall.Producing names " + givesFields.size()
                        + " keys for the " + gives.size() + " fields it gives back");
            }
        }

        @Override
        public ModelCallPurpose purpose() {
            return ModelCallPurpose.PRODUCE;
        }
    }

    /**
     * @param deciding the fields whose values the model was asked to decide, which its answer is read against
     * @param values the value of each field {@code deciding} names, in the same order
     */
    record Reviewing(List<FieldName> deciding, List<ProductionValueId> values) implements Reading {

        Reviewing {
            deciding = List.copyOf(requireNonNull(deciding, "PendingCall.Reviewing deciding must not be null"));
            values = List.copyOf(requireNonNull(values, "PendingCall.Reviewing values must not be null"));
            if (deciding.isEmpty() || deciding.size() != values.size()) {
                throw new IllegalArgumentException("PendingCall.Reviewing names " + values.size() + " values for the "
                        + deciding.size() + " fields it decides, and one at least");
            }
        }

        @Override
        public ModelCallPurpose purpose() {
            return ModelCallPurpose.REVIEW;
        }
    }
}
