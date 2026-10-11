package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.identity.SubjectId;
import java.time.Instant;

/** A stop in force on an entry a run runs. */
public record StopRecord(SubjectId by, Instant since) {

    public StopRecord {
        requireNonNull(by, "StopRecord by must not be null");
        requireNonNull(since, "StopRecord since must not be null");
    }
}
