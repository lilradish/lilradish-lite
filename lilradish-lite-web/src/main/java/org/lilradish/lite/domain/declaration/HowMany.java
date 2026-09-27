package org.lilradish.lite.domain.declaration;

import org.jspecify.annotations.Nullable;

/** Whether a field holds one or many, whatever kind it is; many holding at most one is still many. */
public sealed interface HowMany permits HowMany.One, HowMany.Many {

    record One() implements HowMany {}

    /** @param most the most it may hold, or none where not chosen yet */
    record Many(@Nullable Integer most) implements HowMany {

        public Many {
            if (most != null && most < 1) {
                throw new IllegalArgumentException("HowMany.Many most must be at least one: " + most);
            }
        }
    }
}
