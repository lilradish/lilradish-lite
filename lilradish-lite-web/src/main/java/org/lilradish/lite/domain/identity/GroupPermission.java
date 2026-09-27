package org.lilradish.lite.domain.identity;

/**
 * The whole vocabulary of what somebody may do inside a group. A role bundles these, and each of them
 * is answered within one group and nowhere else.
 *
 * <p>Two rulings decide what may be written here. Nothing the estate confers carries any of these:
 * the estate manages the system's shape and never anybody's work. And every permission that reads
 * content belongs here rather than beside the estate's, which reads what is measured and never what
 * was said.
 *
 * <p>The declaration order is observable: a gate demanding two of these that somebody lacks names
 * the one declared first.
 *
 * <p>The published spelling is written out rather than folded from the constant name, for the reason
 * {@link EstateAct} gives.
 */
public enum GroupPermission {

    /** Who is in the group, and which roles each of them holds. */
    READ_MEMBERSHIP("read_membership"),

    /** Bringing somebody in from the pool, giving them a role here, taking one away, and removing them. */
    CHANGE_MEMBERSHIP("change_membership"),

    /**
     * Starting a run of a workflow this group may use; renaming, stopping and reopening a run the
     * holder may read, changing its ceiling, and asking the help its workflow allows.
     */
    START_RUN("start_run"),

    READ_OWN_RUNS("read_own_runs"),
    READ_ALL_RUNS("read_all_runs"),

    /** Reading the content a model produced inside any run its holder may read. */
    READ_INFERENCE_CONTENT("read_inference_content"),

    /** Making a value's next try: answering it by hand, or asking again whoever the step names. */
    ANSWER_STEP("answer_step"),

    /** Assuring and refusing a value that waits on a person are the one authority, not two. */
    REVIEW_AT_GATE("review_at_gate"),

    AUTHOR_ENTRY("author_entry"),

    /** Putting an entry into service, and approving a run's ceiling being raised or taken away. */
    APPROVE_ENTRY("approve_entry"),

    REVOKE_ENTRY("revoke_entry");

    private final String published;

    GroupPermission(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
