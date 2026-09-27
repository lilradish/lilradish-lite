package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.WorkflowStepId;

/**
 * A try of a code step to be made, nothing of it written yet: the engine's thread writes it, and so records its
 * code as run, only if that try is still the one to make once it holds the tree.
 *
 * @param root the run at the top of {@code run}'s tree, which every write about the try locks
 * @param number the try to be made
 * @param asker the person who asked for it again; none where the run asks it by itself
 */
record PendingCode(
        GroupId group,
        RunId root,
        RunId run,
        WorkflowStepId step,
        int number,
        @Nullable UserId asker) implements Pending {

    PendingCode {
        requireNonNull(group, "PendingCode group must not be null");
        requireNonNull(root, "PendingCode root must not be null");
        requireNonNull(run, "PendingCode run must not be null");
        requireNonNull(step, "PendingCode step must not be null");
        if (number < 1) {
            throw new IllegalArgumentException("PendingCode number must be positive: " + number);
        }
    }
}
