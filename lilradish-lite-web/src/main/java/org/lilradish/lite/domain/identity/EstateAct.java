package org.lilradish.lite.domain.identity;

/**
 * The whole vocabulary of what somebody may do across the estate. An estate role bundles these, and
 * each of them is answered for the system itself rather than inside any group.
 *
 * <p>A vocabulary of its own rather than a corner of {@link GroupPermission} or of {@link
 * SystemPermission}. What a group grants is decided inside that group and reaches nothing out here;
 * what an actor existing in code may do is granted to no person at all. Three tables that never
 * meet, and the type is what keeps a value of one out of the other two.
 *
 * <p>Only what somebody may read is named here. Granting a role, withdrawing one, bringing somebody
 * into the pool and starting a check are all real acts of the estate, and each belongs with the
 * surface that draws the control for it: declared before there is one, an act is a row nothing reads
 * and nothing holds to being right.
 *
 * <p>The published spelling is written out rather than folded from the constant name. This is the
 * wire contract a reader gates its screens on, so renaming one has to be a decision taken here
 * instead of a consequence of a refactor.
 */
public enum EstateAct {

    /** Who this system knows. */
    READ_POOL("read_pool"),

    /** Which groups exist, and who stands in each of them. */
    READ_GROUPS("read_groups"),

    /** Whether the system is behaving, which is a reading taken and never an authority held. */
    READ_SOUNDNESS("read_soundness"),

    /** What the estate measures of itself. */
    READ_MEASUREMENTS("read_measurements");

    private final String published;

    EstateAct(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
