package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import org.lilradish.lite.domain.identity.SubjectId;

/**
 * An attempt that stopped being the answer, and who stopped it. The attempt itself is kept: it is
 * what was actually produced, and every attempt staying visible is the whole reason they are
 * separate rows at all.
 *
 * <p>Two acts end here and they are two shapes, not one with a field that comes and goes: a
 * reviewer sending the step back always declares what the rerun is to vary, so the demand is
 * present exactly when that is what happened.
 */
public sealed interface Supersession {

    SubjectId by();

    Instant at();

    /** The step is to run again, varying the one thing the reviewer named. */
    record ByRerun(SubjectId by, Instant at, Rerun rerun) implements Supersession {

        public ByRerun {
            requireNonNull(by, "ByRerun by must not be null");
            requireNonNull(at, "ByRerun at must not be null");
            requireNonNull(rerun, "ByRerun rerun must not be null");
        }
    }

    /**
     * A person replaced what this attempt said. Nothing more is recorded here: the attempt that
     * carries the replacement is what names this one.
     */
    record ByOverride(SubjectId by, Instant at) implements Supersession {

        public ByOverride {
            requireNonNull(by, "ByOverride by must not be null");
            requireNonNull(at, "ByOverride at must not be null");
        }
    }
}
