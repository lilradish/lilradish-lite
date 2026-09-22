package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

/**
 * Where a question is asked. Every resource binds to exactly one of these, and what a caller may do
 * is answered within one and nowhere else.
 *
 * <p>Sealed over two cases rather than a group identifier with one distinguished value standing for
 * the estate: the estate is beside groups rather than above them, and two kinds that cannot be
 * substituted make "a group's identifier used as the estate" unwritable rather than merely wrong.
 */
public sealed interface Scope {

    /**
     * The estate, held rather than built per ask. A component added to {@link Estate} stops this
     * compiling, which is what keeps it from becoming one chosen value of several.
     */
    Scope ESTATE = new Estate();

    record Group(GroupId groupId) implements Scope {

        public Group {
            requireNonNull(groupId, "Group groupId must not be null");
        }
    }

    /** What binds here is the system itself, which is why a role held here confers nothing in a group. */
    record Estate() implements Scope {}
}
