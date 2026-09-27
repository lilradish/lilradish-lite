/**
 * The pool as the store holds it and as a caller reads and changes it: who is in it, what each of
 * them holds across the estate, and which groups they stand in — never what they hold inside one,
 * that being the group's to know and not the estate's.
 *
 * <p>Granting and withdrawing an estate role are changes made here rather than beside the estate's own
 * reading of roles. A role is held only by somebody in the pool, and what keeps that true — the lock on
 * their stay, and the reading of the person that answers each change — is this package's alone.
 *
 * <p>The lock on a person's current stay is this package's: taken shared by whatever puts them into a
 * group, exclusively by whatever takes them out of the pool.
 */
@NullMarked
package org.lilradish.lite.app.pool;

import org.jspecify.annotations.NullMarked;
