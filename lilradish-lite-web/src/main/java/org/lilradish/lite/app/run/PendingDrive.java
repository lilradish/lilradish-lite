package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.run.RunId;

/**
 * A tree to go on in a transaction of its own, the one that planned it having started a run beneath already: its
 * address only, its lock taken afresh there.
 */
record PendingDrive(GroupId group, RunId root) implements Pending {

    PendingDrive {
        requireNonNull(group, "PendingDrive group must not be null");
        requireNonNull(root, "PendingDrive root must not be null");
    }
}
