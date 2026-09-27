package org.lilradish.lite.domain.registry;

/**
 * Every standing there is; stopping is a switch on the entry, never a fifth. Read off marks present or
 * absent, never off one time compared with another.
 *
 * <p>The published spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum VersionStanding {
    DRAFT("draft"),
    SUBMITTED("submitted"),
    IN_SERVICE("in_service"),
    RETIRED("retired");

    private final String published;

    VersionStanding(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    /** Approval outranks an open submission, which approving leaves standing as its record. */
    public static VersionStanding of(boolean submitted, boolean approved, boolean retired) {
        if (retired && !approved) {
            throw new IllegalArgumentException("VersionStanding cannot be read off a version retired unapproved");
        }
        if (retired) {
            return RETIRED;
        }
        if (approved) {
            return IN_SERVICE;
        }
        return submitted ? SUBMITTED : DRAFT;
    }
}
