package org.lilradish.lite.domain.inference;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * One time a model would not take a call, before anything was taken up. When is not carried: it is
 * the moment the turnaway is recorded.
 *
 * @param said what the model said in turning it away, absent where it said nothing, never empty
 * @param spentUp it said what may be spent with the model is used up, rather than that it is busy
 */
public record TurnAway(@DoNotLog @Nullable String said, boolean spentUp) {

    public TurnAway {
        if (said != null && said.isEmpty()) {
            throw new IllegalArgumentException("TurnAway said is empty; nothing said is absent");
        }
    }
}
