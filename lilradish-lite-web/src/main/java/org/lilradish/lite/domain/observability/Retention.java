package org.lilradish.lite.domain.observability;

import static java.util.Objects.requireNonNull;

/**
 * How long a category is kept, and — where it expires — how. A stated maximum and a
 * life-of-deployment keep are two shapes, not one shape with fields that are sometimes absent: an
 * absent maximum cannot be written here at all.
 */
public sealed interface Retention {

    int minimumDays();

    /**
     * Not every window is served by deleting rows: inference content expires by redacting a step
     * row that must itself survive, because a run record is written once and never revised.
     */
    enum ExpiryMethod {
        SWEPT,
        REDACTED
    }

    record FixedWindow(int minimumDays, int maximumDays, ExpiryMethod method) implements Retention {

        public FixedWindow {
            requireDays("FixedWindow", minimumDays);
            if (maximumDays < minimumDays) {
                throw new IllegalArgumentException(
                        "FixedWindow maximumDays " + maximumDays + " is below minimumDays " + minimumDays);
            }
            requireNonNull(method, "FixedWindow method must not be null");
        }
    }

    /** No expiry method, because nothing expires: the keep is the whole statement. */
    record DeploymentLife(int minimumDays) implements Retention {

        public DeploymentLife {
            requireDays("DeploymentLife", minimumDays);
        }
    }

    private static void requireDays(String shape, int minimumDays) {
        if (minimumDays < 0) {
            throw new IllegalArgumentException(shape + " minimumDays must not be negative: " + minimumDays);
        }
    }
}
