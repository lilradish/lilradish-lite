package com.lilradish.lite.domain.listing;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/** Returns the store's actual sort value, never a recomputed one: a page resumes from it. */
public interface ListRow<C extends Enum<C> & ListColumn> {

    @DoNotLog
    @Nullable
    Object valueIn(C column);
}
