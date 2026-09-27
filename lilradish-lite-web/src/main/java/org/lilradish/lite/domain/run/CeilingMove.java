package org.lilradish.lite.domain.run;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** What asking a run's ceiling to be another does, judged against the ceiling in force. */
public enum CeilingMove {
    UNCHANGED,

    /** Lowered, given where there was none, or raised or taken away where the version asks no approval of that. */
    HOLDS_AT_ONCE,

    /** Not in force until approved: in force first, every call made meanwhile would spend against it. */
    AWAITS_APPROVAL;

    /** Taking a ceiling away is a raise, as far as any raise goes. */
    public static CeilingMove of(@Nullable Ceiling inForce, @Nullable Ceiling asked, boolean raiseNeedsApproval) {
        if (Objects.equals(inForce, asked)) {
            return UNCHANGED;
        }
        boolean raised = inForce != null && (asked == null || asked.value() > inForce.value());
        return raised && raiseNeedsApproval ? AWAITS_APPROVAL : HOLDS_AT_ONCE;
    }
}
