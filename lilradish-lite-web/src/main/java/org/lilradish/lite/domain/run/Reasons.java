package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.text.Legibility;
import org.lilradish.lite.domain.text.ProseRefusal;

/**
 * What a person's reason for what they produced, and the words a value is refused with, are held to: prose a
 * model may be sent, over lines, at most 2048 characters. One holding nothing that shows is no reason at all.
 */
public final class Reasons {

    /** Level with the columns' own bound, and counted the way those checks count. */
    public static final int LONGEST = 2048;

    private Reasons() {}

    /** Whether one was given: absent, and one of spaces, tabs, line feeds and the like alone, are none. */
    public static boolean given(@Nullable String said) {
        return said != null && Legibility.holdsSomethingVisibleInProse(said);
    }

    /** Why one given is refused, in the order a page looks too; none where it is taken. */
    public static @Nullable ProseRefusal refusalOf(String said) {
        requireNonNull(said, "Reasons said must not be null");
        return ProseRefusal.of(said, true, Reasons::requireWellFormed);
    }

    /**
     * What one sent is refused with, none where it is taken. What a page names apart is named first, since it can
     * be what hides the rest; then one showing nothing is none at all; then anything else prose cannot hold.
     */
    public static @Nullable RefusalCode judged(@Nullable String said) {
        ProseRefusal refused = said == null ? null : refusalOf(said);
        if (refused != null && refused != ProseRefusal.UNUSABLE) {
            return refused.code(RefusalCode.REASON_UNUSABLE);
        }
        if (!given(said)) {
            return RefusalCode.REASON_MISSING;
        }
        return refused == null ? null : RefusalCode.REASON_UNUSABLE;
    }

    private static void requireWellFormed(String said) {
        Legibility.requireWellFormedProse(said, "Reason");
        Legibility.requireSomethingVisibleInProse(said, "Reason");
        Legibility.requireWithinMaximumLength(said, LONGEST, "Reason");
    }
}
