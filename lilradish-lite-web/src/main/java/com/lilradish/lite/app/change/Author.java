package com.lilradish.lite.app.change;

/**
 * A caller with no subject leaves the author null, which the store refuses rather than records.
 */
public final class Author {

    public static final String OF_CALLER =
            "(select author.subject_id from subjects author where author.user_id = :caller)";

    private Author() {}
}
