package org.lilradish.lite.app.listing;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.listing.ListColumn;

/**
 * What a column a list sorts by is in SQL: an expression over the list's relation, and for text the
 * collation it is ordered and compared under. Both are constants of the code declaring the list, and
 * nothing a reader sends ever reaches either.
 *
 * <p>Text names its collation, the database's default not being this code's to choose; a value no
 * collation applies to names none.
 *
 * <p>A position's value is compared with the expression as its bound parameter is written into
 * {@code positionAs} at {@code %s}: as itself, unless the expression is of a type a position cannot hold.
 *
 * @param collation the collation as SQL writes its name, quoted where SQL needs it quoted
 */
public record SortExpression<C extends Enum<C> & ListColumn>(
        C column, String expression, @Nullable String collation, String positionAs) {

    private static final String AS_BOUND = "%s";

    public SortExpression {
        requireNonNull(column, "SortExpression column must not be null");
        requireNonNull(expression, "SortExpression expression must not be null");
        requireNonNull(positionAs, "SortExpression position form must not be null");
        if (expression.isBlank()) {
            throw new IllegalArgumentException("SortExpression expression must not be blank");
        }
        if (positionAs.indexOf(AS_BOUND) < 0 || positionAs.indexOf(AS_BOUND) != positionAs.lastIndexOf(AS_BOUND)) {
            throw new IllegalArgumentException("SortExpression position form must name its parameter once");
        }
        boolean text =
                switch (column.kind()) {
                    case TEXT, NULLABLE_TEXT -> true;
                    case LONG, BOOLEAN -> false;
                };
        if (text && (collation == null || collation.isBlank())) {
            throw new IllegalArgumentException("SortExpression over text must name its collation");
        }
        if (!text && collation != null) {
            throw new IllegalArgumentException("SortExpression over anything but text must name no collation");
        }
    }

    /** Compared with a position's value as it is bound. */
    public SortExpression(C column, String expression, @Nullable String collation) {
        this(column, expression, collation, AS_BOUND);
    }

    /** Bracketed before its collation is named, which would otherwise bind to its last term alone. */
    String compared() {
        return collation == null ? expression : "(" + expression + ") collate " + collation;
    }

    String positioned(String parameter) {
        return positionAs.replace(AS_BOUND, parameter);
    }
}
