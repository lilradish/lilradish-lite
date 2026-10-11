package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.inference.ModelCallPurpose;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunStepFailureId;
import com.lilradish.lite.domain.run.RunStepSendAttemptId;
import com.lilradish.lite.domain.run.WorkflowStepId;
import org.jspecify.annotations.Nullable;

/**
 * A model's try to be sent, to be produced or to be reviewed, nothing of its sending written yet: the engine's
 * thread writes it down as sent, and sends it, only if it is still the run's next act, or pressed, still offered on
 * what was pressed, once it holds the tree.
 *
 * @param root the run at the top of {@code run}'s tree, which every write about the try locks
 * @param aTry the production to be sent, which is its step's newest
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
        ProductionId aTry,
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
        requireNonNull(aTry, "PendingSend aTry must not be null");
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
