package org.lilradish.lite.domain.identity;

/**
 * The whole vocabulary of what somebody may do across the estate. An estate role bundles these, and
 * each of them is answered for the system itself rather than inside any group.
 *
 * <p>A vocabulary of its own rather than a corner of {@link GroupPermission}: what a group grants is
 * inside a group reaches nothing out here, and the type keeps a value of one out of the other.
 *
 * <p>An act is named here once something answers for it, and not before: declared ahead of the
 * surface that draws its control, an act is a row nothing reads and nothing holds to being right.
 *
 * <p>The published spelling is written out rather than folded from the constant name. This is the
 * wire contract a reader gates its screens on, so renaming one has to be a decision taken here
 * instead of a consequence of a refactor.
 */
public enum EstateAct {

    /** Who this system is used by: seeing who is in the pool, bringing somebody in, taking somebody out. */
    KEEP_POOL("keep_pool"),

    /** Which estate roles somebody in the pool holds: granting one, withdrawing one. */
    GRANT_ESTATE_ROLE("grant_estate_role"),

    /** Which groups exist: seeing every one of them, creating one, renaming one. */
    KEEP_GROUP_REGISTER("keep_group_register"),

    /** How much of each group's work still holds together: reading the last check, starting one. */
    CHECK_SOUNDNESS("check_soundness"),

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
