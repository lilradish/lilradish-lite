package org.lilradish.lite.app.change;

/**
 * Who made a change: the caller's subject, looked up inside the statement recording it, from the user
 * bound as {@code :caller}. A caller no subject answers for leaves it null, which the store refuses
 * rather than records.
 */
public final class Author {

    public static final String OF_CALLER =
            "(select author.subject_id from subjects author where author.user_id = :caller)";

    private Author() {}
}
