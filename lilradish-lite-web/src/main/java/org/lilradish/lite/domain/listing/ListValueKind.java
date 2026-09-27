package org.lilradish.lite.domain.listing;

/**
 * The kinds of value a list is sorted by, as a position holds them: text as a {@link String}, a count
 * as a {@link Long}, a fact that holds or does not as a {@link Boolean}, which sorts false first.
 *
 * <p>Text is sortable only where the store refuses it empty, longer than 256 characters, or holding a
 * control character. A position is refused on the same terms, and on no others.
 */
public enum ListValueKind {
    TEXT(false),

    NULLABLE_TEXT(true),

    LONG(false),

    BOOLEAN(false);

    private final boolean nullable;

    ListValueKind(boolean nullable) {
        this.nullable = nullable;
    }

    /** Whether a row may hold nothing here, which sorts after every row holding something. */
    public boolean nullable() {
        return nullable;
    }
}
