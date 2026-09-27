package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;

/** What a model reviewing a try is sent beside the envelope, or why that could not be built. */
public sealed interface ReviewPayload permits ReviewPayload.Built, ReviewPayload.Unbuilt {

    record Built(@DoNotLog JsonObject payload) implements ReviewPayload {

        public Built {
            requireNonNull(payload, "ReviewPayload.Built payload must not be null");
        }
    }

    record Unbuilt(ReviewUnbuiltReason reason) implements ReviewPayload {

        public Unbuilt {
            requireNonNull(reason, "ReviewPayload.Unbuilt reason must not be null");
        }
    }
}
