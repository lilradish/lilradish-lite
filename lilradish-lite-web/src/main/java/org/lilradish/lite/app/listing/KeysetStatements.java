package org.lilradish.lite.app.listing;

import static java.util.Objects.requireNonNull;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListFilter;
import org.lilradish.lite.domain.listing.ListOrder;
import org.lilradish.lite.domain.listing.ListPage;
import org.lilradish.lite.domain.listing.ListPosition;
import org.lilradish.lite.domain.listing.ListQuery;
import org.lilradish.lite.domain.listing.ListRow;
import org.lilradish.lite.domain.listing.ListShape;
import org.lilradish.lite.domain.people.PersonName;

/**
 * Every statement a list is read with, written once when the list is declared: one for each column and
 * direction, narrowed by the list's filter or not, from the start or after a position.
 *
 * <p>A page after a position is read with the comparison written out term by term, never as one row
 * compared with another. A row comparison answers null at the first null it meets, which drops every
 * row holding nothing in the column sorted by, and it runs every term one way, where a tie is broken
 * ascending whichever way the column runs. Somebody holding nothing sorts after everybody holding
 * something, in both directions, so no order opens on a block of blanks.
 *
 * <p>The relation is a statement ending in a where clause of one condition, which every other is joined
 * to by {@code and}. Neither it nor the filter condition binds a parameter of its own besides
 * {@link #TYPED}, {@link #TYPED_AS_NAMES}, {@link #WITHIN} and those the list is declared with, and every
 * value reaches a statement as a parameter.
 *
 * <p>A list read within something narrows by the query's own scope, the value its cursors are bound to,
 * rather than by one handed in beside it: two values could name two scopes, and a cursor minted in one
 * would then page through the other. Its relation names {@link #WITHIN}, and only then may the filter
 * condition name it too.
 */
public final class KeysetStatements<C extends Enum<C> & ListColumn> {

    /** The parameter a filter condition matches what was typed by. */
    public static final String TYPED = ":filter";

    /** What was typed with each run of white space one space, for matching text held spaced as names are. */
    public static final String TYPED_AS_NAMES = ":filterSpaced";

    /** The parameter a relation read within a scope names it by, bound to the query's scope as spelt. */
    public static final String WITHIN = ":within";

    private static final Pattern NAMES_WITHIN = Pattern.compile(WITHIN + "\\b");

    private static final Pattern NAMES_TYPED_AS_NAMES = Pattern.compile(TYPED_AS_NAMES + "\\b");

    private static final String FILTER = "filter";

    private static final String FILTER_SPACED = "filterSpaced";

    private static final String SCOPE = "within";

    private static final String AFTER_VALUE = "afterValue";

    private static final String AFTER_TIE = "afterTie";

    private static final String LIMIT = "limit";

    private static final int FROM_THE_START = 0;

    private static final int AFTER_A_VALUE = 1;

    private static final int AFTER_NOTHING_HELD = 2;

    private static final int WAYS_TO_BEGIN = 3;

    private static final boolean[] BOTH = {false, true};

    private static final Set<String> RESERVED = Set.of(FILTER, FILTER_SPACED, SCOPE, AFTER_VALUE, AFTER_TIE, LIMIT);

    private final ListShape<C> shape;

    private final @Nullable String[] statements;

    private final Map<String, Object> bound;

    private final boolean readWithin;

    private final boolean matchedAsNames;

    /**
     * The shape is made here and answered by {@link #shape()}, never handed in: what the relation binds is
     * in no cursor, so a shape shared by two declarations would let one's cursors page through the other.
     *
     * @param name the list's name, as {@link ListShape} takes it
     * @param bound what the relation binds besides what a reader asks, the same for every statement
     */
    public KeysetStatements(
            String name,
            C tiebreak,
            ListOrder<C> opening,
            String relation,
            String filtered,
            List<SortExpression<C>> expressions,
            Map<String, Object> bound) {
        ListShape<C> shape = new ListShape<>(name, tiebreak, opening);
        requireNonNull(relation, "KeysetStatements relation must not be null");
        requireNonNull(filtered, "KeysetStatements filter condition must not be null");
        requireNonNull(expressions, "KeysetStatements expressions must not be null");
        requireNonNull(bound, "KeysetStatements bound parameters must not be null");
        if (bound.keySet().stream().anyMatch(RESERVED::contains)) {
            throw new IllegalArgumentException("KeysetStatements binds a parameter a reader's query binds");
        }
        Class<C> columnType = shape.tiebreak().getDeclaringClass();
        Map<C, SortExpression<C>> byColumn = new EnumMap<>(columnType);
        for (SortExpression<C> expression : expressions) {
            if (byColumn.put(expression.column(), expression) != null) {
                throw new IllegalArgumentException("KeysetStatements declares one column twice");
            }
        }
        C[] columns = columnType.getEnumConstants();
        if (byColumn.size() != columns.length) {
            throw new IllegalArgumentException(
                    "KeysetStatements declares no expression for a column the list sorts by");
        }
        SortExpression<C> tie = requireNonNull(byColumn.get(shape.tiebreak()));
        this.shape = shape;
        this.bound = Map.copyOf(bound);
        this.readWithin = NAMES_WITHIN.matcher(relation).find();
        this.matchedAsNames = NAMES_TYPED_AS_NAMES.matcher(filtered).find();
        if (!readWithin && NAMES_WITHIN.matcher(filtered).find()) {
            throw new IllegalArgumentException(
                    "KeysetStatements filter condition reads a scope its relation is not read within");
        }
        this.statements = new String[columns.length * BOTH.length * BOTH.length * WAYS_TO_BEGIN];
        for (C column : columns) {
            SortExpression<C> sorted = requireNonNull(byColumn.get(column));
            for (boolean descending : BOTH) {
                for (boolean narrowed : BOTH) {
                    for (int begin = FROM_THE_START; begin < WAYS_TO_BEGIN; begin++) {
                        if (begin != AFTER_NOTHING_HELD || column.kind().nullable()) {
                            statements[index(column, descending, narrowed, begin)] =
                                    written(relation, filtered, sorted, tie, descending, narrowed, begin);
                        }
                    }
                }
            }
        }
    }

    /** The list these statements read, which every cursor minted or resumed for it is bound to. */
    public ListShape<C> shape() {
        return shape;
    }

    String statement(ListQuery<C> query, @Nullable ListPosition after) {
        ListOrder<C> order = query.order();
        return requireNonNull(statements[
                index(order.column(), order.descending(), query.filter() != null, begin(order.column(), after))]);
    }

    Map<String, Object> parameters(ListQuery<C> query, @Nullable ListPosition after) {
        if (readWithin == query.scope().isEmpty()) {
            throw new IllegalArgumentException(
                    readWithin
                            ? "KeysetStatements list read within a scope was asked for within none"
                            : "KeysetStatements list read whole was asked for within a scope");
        }
        Map<String, Object> parameters = new HashMap<>(bound);
        if (readWithin) {
            parameters.put(SCOPE, query.scope());
        }
        ListFilter filter = query.filter();
        if (filter != null) {
            parameters.put(FILTER, filter.text());
            if (matchedAsNames) {
                parameters.put(FILTER_SPACED, PersonName.spacedAsNamesAre(filter.text()));
            }
        }
        if (after != null) {
            parameters.put(AFTER_TIE, after.tiebreakValue());
            Object sortValue = after.sortValue();
            if (sortValue != null && query.order().column() != shape.tiebreak()) {
                parameters.put(AFTER_VALUE, sortValue);
            }
        }
        parameters.put(LIMIT, ListPage.SIZE + 1);
        return parameters;
    }

    /** What a statement fetched, as a page of the list these statements were declared for. */
    <R extends ListRow<C>> ListPage<R> page(List<R> fetched, ListQuery<C> query) {
        return ListPage.of(fetched, shape, query.order());
    }

    private static int begin(ListColumn column, @Nullable ListPosition after) {
        if (after == null) {
            return FROM_THE_START;
        }
        if (after.sortValue() != null) {
            return AFTER_A_VALUE;
        }
        if (column.kind().nullable()) {
            return AFTER_NOTHING_HELD;
        }
        throw new IllegalArgumentException("KeysetStatements position holds nothing where every row holds something");
    }

    private static int index(Enum<?> column, boolean descending, boolean narrowed, int begin) {
        return ((column.ordinal() * BOTH.length + (descending ? 1 : 0)) * BOTH.length + (narrowed ? 1 : 0))
                        * WAYS_TO_BEGIN
                + begin;
    }

    // DB-SPECIFIC: nulls last and limit are PostgreSQL's spellings.
    private static <C extends Enum<C> & ListColumn> String written(
            String relation,
            String filtered,
            SortExpression<C> sorted,
            SortExpression<C> tie,
            boolean descending,
            boolean narrowed,
            int begin) {
        String sortedBy = sorted.compared();
        String tiebreak = tie.compared();
        String nothingHeld = "(" + sorted.expression() + ") is null";
        boolean byTiebreak = sorted.column() == tie.column();
        boolean nullable = sorted.column().kind().nullable();
        String beyond = descending ? " < " : " > ";
        String afterValue = sorted.positioned(":" + AFTER_VALUE);
        String afterTie = tie.positioned(":" + AFTER_TIE);
        StringBuilder written = new StringBuilder(relation);
        if (narrowed) {
            written.append(" and (").append(filtered).append(')');
        }
        if (begin == AFTER_A_VALUE && byTiebreak) {
            written.append(" and ").append(tiebreak).append(beyond).append(afterTie);
        } else if (begin == AFTER_A_VALUE) {
            written.append(" and (");
            if (nullable) {
                written.append(nothingHeld).append(" or ");
            }
            written.append(sortedBy).append(beyond).append(afterValue);
            written.append(" or (").append(sortedBy).append(" = ").append(afterValue);
            written.append(" and ")
                    .append(tiebreak)
                    .append(" > ")
                    .append(afterTie)
                    .append("))");
        } else if (begin == AFTER_NOTHING_HELD) {
            written.append(" and ").append(nothingHeld);
            written.append(" and ").append(tiebreak).append(" > ").append(afterTie);
        }
        written.append(" order by ").append(sortedBy).append(descending ? " desc" : " asc");
        if (nullable) {
            written.append(" nulls last");
        }
        if (!byTiebreak) {
            written.append(", ").append(tiebreak).append(" asc");
        }
        return written.append(" limit :").append(LIMIT).toString();
    }
}
