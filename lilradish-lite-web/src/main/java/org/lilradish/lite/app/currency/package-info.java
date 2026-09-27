/**
 * The currency one group reads its costs in, as the store holds it and as a member reads and chooses it:
 * none until somebody chooses one, and after that changed and never cleared.
 *
 * <p>What is read here is narrowed to the group the gate admitted the caller into and to nothing else,
 * so the permission a handler asks there is the only guard and nothing outside this package reaches it.
 */
@NullMarked
package org.lilradish.lite.app.currency;

import org.jspecify.annotations.NullMarked;
