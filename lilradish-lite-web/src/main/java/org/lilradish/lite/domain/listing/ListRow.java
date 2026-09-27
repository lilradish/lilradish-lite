package org.lilradish.lite.domain.listing;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * A row of a list, asked what it holds in a column the list sorts by. The answer has to be the very
 * value the store sorted it by, read back, and never one worked out again from anything else: a page
 * resumes from it.
 */
public interface ListRow<C extends Enum<C> & ListColumn> {

    @DoNotLog
    @Nullable
    Object valueIn(C column);
}
