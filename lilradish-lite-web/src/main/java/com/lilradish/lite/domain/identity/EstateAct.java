package com.lilradish.lite.domain.identity;

/**
 * Estate roles grant these system-wide acts; group permissions grant none of them.
 * Only name an act once something answers for it, so the vocabulary has no unverified entries.
 *
 * <p>Published spellings are explicit wire contracts used to gate screens; renaming a constant must not change them.
 */
public enum EstateAct {

    /** Seeing who is in the pool, bringing somebody in, and taking somebody out. */
    KEEP_POOL("keep_pool"),

    /** Which estate roles somebody in the pool holds: granting one, withdrawing one. */
    GRANT_ESTATE_ROLE("grant_estate_role"),

    /** Which groups exist: seeing every one of them, creating one, renaming one. */
    KEEP_GROUP_REGISTER("keep_group_register"),

    /** How much of each group's work still holds together: reading the last check, starting one. */
    CHECK_SOUNDNESS("check_soundness"),

    READ_MEASUREMENTS("read_measurements");

    private final String published;

    EstateAct(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
