package org.lilradish.lite.domain.identity;

/**
 * The whole vocabulary of what somebody may do inside a group. A role bundles these, a delegation's
 * declared permissions are a subset of these, and each of them is answered within one group and
 * nowhere else.
 *
 * <p>Two rulings decide what may be written here. Nothing the estate confers carries any of these:
 * the estate manages the system's shape and never anybody's work. And every permission that reads
 * content belongs here rather than beside the estate's, which reads what is measured and never what
 * was said.
 */
public enum GroupPermission {

    /** Who is in the group, and which roles each of them holds. */
    READ_MEMBERSHIP,

    /** Granting a role here, withdrawing one, and bringing somebody in from the pool of people. */
    CHANGE_MEMBERSHIP,

    START_RUN,
    READ_OWN_RUNS,
    READ_ALL_RUNS,

    /**
     * Meant to be insufficient on its own: what it admits is a read that also states a purpose and
     * is recorded against the reader. Neither is demanded anywhere yet, so this currently reaches
     * further than it is meant to — both are owed by whatever comes to serve the content.
     */
    READ_INFERENCE_CONTENT,

    /** Assuring and refusing a value that waits on a person are the one authority, not two. */
    REVIEW_AT_GATE,

    AUTHOR_ENTRY,
    APPROVE_ENTRY,
    REVOKE_ENTRY,

    /** An initiator's token ceiling is not something its holder may declare for itself. */
    DECLARE_TOKEN_CEILING,

    /** Where the group's work arrives from — a mailbox, a tracker, a paste box. */
    DECLARE_WORK_SOURCE,

    /** Letting other groups use an entry, which is a further act on one already in service. */
    PUBLISH_ENTRY,

    /** Settling a piece of work as needing nothing, against a stated reason. */
    DISMISS_WORK,

    RAISE_RUN_ALLOWANCE
}
