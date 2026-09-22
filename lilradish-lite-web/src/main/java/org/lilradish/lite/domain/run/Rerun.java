package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * What a reviewer demanded when they sent a step back. Written on the attempt being sent back,
 * because the replacement has no row until the step runs again and the demand has to survive that
 * gap.
 *
 * <p>The note is not a comment on the work: it is handed to the next call as an argument, so it
 * leaves this process and answers to whatever ceiling the step declared for what it may send. A
 * blank one is no note and is held as none: kept as written, two reruns stating nothing would
 * compare unequal.
 *
 * <p>Why the reviewer wanted the rerun is not recorded, and that is the decision rather than an
 * omission: a rerun is an experiment, not a judgement about the work. What a run teaches is drawn
 * from rejections and overrides, which say what was wrong — not from what someone wanted to try.
 */
public record Rerun(RerunVariant vary, @DoNotLog @Nullable String note) {

    public Rerun {
        requireNonNull(vary, "Rerun vary must not be null");
        boolean stated = note != null && !note.isBlank();
        if ((vary == RerunVariant.REVIEWER_NOTE) != stated) {
            throw new IllegalArgumentException(
                    "Rerun carries a note exactly when the note is what varies, and this one varies " + vary);
        }
        note = stated ? note : null;
    }
}
