package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import org.lilradish.lite.domain.identity.SubjectId;

/**
 * A stop in force on an entry a run runs, and who made it.
 *
 * @param by the person who stopped it
 */
public record StopRecord(SubjectId by, Instant since) {

    public StopRecord {
        requireNonNull(by, "StopRecord by must not be null");
        requireNonNull(since, "StopRecord since must not be null");
    }
}
