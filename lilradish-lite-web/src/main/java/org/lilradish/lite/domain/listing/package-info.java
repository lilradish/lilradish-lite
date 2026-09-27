/**
 * How any list is asked to be read: in which order, narrowed by what, and from where a page left off,
 * and the cursor that carries a reader from one page to the next. Each is refused rather than read as
 * the nearest thing it resembles, because an answer to a query the reader did not ask is a list that
 * looks like an answer to their question and is not.
 */
@NullMarked
package org.lilradish.lite.domain.listing;

import org.jspecify.annotations.NullMarked;
