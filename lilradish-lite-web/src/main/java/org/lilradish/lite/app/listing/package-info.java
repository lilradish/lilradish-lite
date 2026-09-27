/**
 * Reading any list out of the store a page at a time: the statement each way of asking for it is read
 * with, worked out once when the list is declared; the page, and the answer a reader receives; the
 * refusal each part of a query that cannot be read is answered with. Also how anything typed is
 * matched against what is held, which every filter and every search shares.
 *
 * <p>Nothing here decides who may read a list. What a list is read within is handed in only once the
 * caller has been let through to it.
 */
@NullMarked
package org.lilradish.lite.app.listing;

import org.jspecify.annotations.NullMarked;
