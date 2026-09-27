package org.lilradish.lite.app.listing;

/**
 * Whether held text holds what a reader typed, as one SQL condition. The held side is the fold stored
 * beside it; the typed side goes through the same fold once per query.
 *
 * <p>The fold decomposes, case-folds and recomposes, so a bare letter misses one its accent composes
 * with; it is Unicode's default fold and not Turkish, so a dotted capital I matches no plain i, and
 * that is accepted. Matched as text anywhere in the held value, so no character typed means anything
 * but itself.
 */
public final class SearchFolding {

    private SearchFolding() {}

    // DB-SPECIFIC: strpos is PostgreSQL's; search_fold is defined in V1 with PostgreSQL-only attributes.
    public static String holds(String foldedColumn, String typedParameter) {
        return "strpos(" + foldedColumn + ", (select search_fold(" + typedParameter + "))) > 0";
    }
}
