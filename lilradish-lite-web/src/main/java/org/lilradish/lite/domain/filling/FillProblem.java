package org.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

/** One filled value that does not fit its field, where it stands and why. */
public record FillProblem(FillPath path, FillReason reason) {

    public FillProblem {
        requireNonNull(path, "FillProblem path must not be null");
        requireNonNull(reason, "FillProblem reason must not be null");
    }
}
