package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/** One attempt to send a try to a model, to produce it or to review it: written whether or not it was sent. */
public record RunStepSendAttemptId(UUID value) {

    public RunStepSendAttemptId {
        requireNonNull(value, "RunStepSendAttemptId must not be null");
    }
}
