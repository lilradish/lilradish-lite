package com.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

public record FillProblem(FillPath path, FillReason reason) {

    public FillProblem {
        requireNonNull(path, "FillProblem path must not be null");
        requireNonNull(reason, "FillProblem reason must not be null");
    }
}
