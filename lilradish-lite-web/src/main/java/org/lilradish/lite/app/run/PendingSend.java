package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.inference.ModelCallPurpose;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunStepFailureId;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.WorkflowStepId;

/**
 * A model's try to be sent, to be produced or to be reviewed, nothing of its sending written yet: the engine's
 * thread writes it down as sent, and sends it, only if it is still the run's next act, or pressed, still offered on
 * what was pressed, once it holds the tree.
 *
 * @param root the run at the top of {@code run}'s tree, which every write about the try locks
 * @param number the try to be sent, which is its step's newest
 * @param presser the person who pressed Try sending, whose attempt what is sent is; none where the run sends it by
 *     itself. What is measured too long, or not built, is the system's whoever pressed.
 * @param attemptOn the attempt pressed on: the one a hold names, or the one whose call to review was turned away;
 *     none where a failure was, or nobody pressed
 * @param failedOn the failure pressed on; none where an attempt was, or nobody pressed
 */
record PendingSend(
        GroupId group,
        RunId root,
        RunId run,
        WorkflowStepId step,
        int number,
        ModelCallPurpose purpose,
        @Nullable UserId presser,
        @Nullable RunStepSendAttemptId attemptOn,
        @Nullable RunStepFailureId failedOn)
        implements Pending {

    PendingSend {
        requireNonNull(group, "PendingSend group must not be null");
        requireNonNull(root, "PendingSend root must not be null");
        requireNonNull(run, "PendingSend run must not be null");
        requireNonNull(step, "PendingSend step must not be null");
        requireNonNull(purpose, "PendingSend purpose must not be null");
        if (number < 1) {
            throw new IllegalArgumentException("PendingSend number must be positive: " + number);
        }
        if (purpose == ModelCallPurpose.HELP) {
            throw new IllegalArgumentException("PendingSend sends a try to be produced or reviewed");
        }
        int named = (attemptOn == null ? 0 : 1) + (failedOn == null ? 0 : 1);
        if (named != (presser == null ? 0 : 1)) {
            throw new IllegalArgumentException(
                    "PendingSend names the one attempt or failure pressed on exactly where it was pressed");
        }
    }
}
