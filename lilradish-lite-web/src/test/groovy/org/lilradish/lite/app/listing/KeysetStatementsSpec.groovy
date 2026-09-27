package org.lilradish.lite.app.listing

import groovy.transform.TupleConstructor
import org.lilradish.lite.domain.listing.ListFilter
import org.lilradish.lite.domain.listing.ListOrder
import org.lilradish.lite.domain.listing.ListPage
import org.lilradish.lite.domain.listing.ListPosition
import org.lilradish.lite.domain.listing.ListQuery
import org.lilradish.lite.domain.listing.ListRow
import org.lilradish.lite.domain.listing.ListShape
import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

/**
 * The text of every statement, compared whole: what each one returns is the store's to answer and is
 * asked of a real server elsewhere, but which conditions a statement carries, in which direction, and
 * which values it binds can be read off the text alone.
 */
class KeysetStatementsSpec extends Specification {

    @TupleConstructor
    static class Row implements ListRow<SampleColumn> {
        String key
        String name
        Long size

        @Override
        Object valueIn(SampleColumn column) {
            switch (column) {
                case SampleColumn.KEY: return key
                case SampleColumn.NAME: return name
                default: return size
            }
        }
    }

    static final ListOrder<SampleColumn> BY_KEY = new ListOrder<>(SampleColumn.KEY, false)

    static final ListOrder<SampleColumn> BY_KEY_DESCENDING = new ListOrder<>(SampleColumn.KEY, true)

    static final ListOrder<SampleColumn> BY_NAME = new ListOrder<>(SampleColumn.NAME, false)

    static final ListOrder<SampleColumn> BY_NAME_DESCENDING = new ListOrder<>(SampleColumn.NAME, true)

    static final ListOrder<SampleColumn> BY_SIZE = new ListOrder<>(SampleColumn.SIZE, false)

    static final ListOrder<SampleColumn> BY_SIZE_DESCENDING = new ListOrder<>(SampleColumn.SIZE, true)

    static final List<ListOrder<SampleColumn>> EVERY_ORDER =
            [BY_KEY, BY_KEY_DESCENDING, BY_NAME, BY_NAME_DESCENDING, BY_SIZE, BY_SIZE_DESCENDING]

    static final String RELATION = "select * from t where true"

    static final String FILTERED = "f(t.name, :filter) or f(t.key, :filter)"

    static final SortExpression<SampleColumn> KEY_EXPRESSION = new SortExpression<>(SampleColumn.KEY, "t.key", "ucs_basic")

    static final SortExpression<SampleColumn> NAME_EXPRESSION = new SortExpression<>(SampleColumn.NAME, "t.name", '"unicode"')

    static final SortExpression<SampleColumn> SIZE_EXPRESSION = new SortExpression<>(SampleColumn.SIZE, "t.size", null)

    static final List<SortExpression<SampleColumn>> EVERY_EXPRESSION = [KEY_EXPRESSION, NAME_EXPRESSION, SIZE_EXPRESSION]

    static final String KEY = "(t.key) collate ucs_basic"

    static final String NAME = '(t.name) collate "unicode"'

    static final ListFilter GRACE = new ListFilter("grace")

    static final ListPosition AFTER_KEY = new ListPosition("k150", "k150")

    static final ListPosition AFTER_NAME = new ListPosition("grace hopper", "k150")

    static final ListPosition AFTER_NAMELESS = new ListPosition(null, "k150")

    static final ListPosition AFTER_SIZE = new ListPosition(2L, "k150")

    static final Map<SampleColumn, List<ListPosition>> AFTER_EACH = [
            (SampleColumn.KEY) : [null, AFTER_KEY],
            (SampleColumn.NAME): [null, AFTER_NAME, AFTER_NAMELESS],
            (SampleColumn.SIZE): [null, AFTER_SIZE],
    ]

    /** Every way a list is read: each order, narrowed or not, from the start or from each position it can resume at. */
    static final List<List<Object>> EVERY_READING = EVERY_ORDER.collectMany { order ->
        [[null, GRACE], AFTER_EACH[order.column()]].combinations().collect { [order] + it }
    }

    /** A page and one beyond it. */
    static final List<Row> PAGE_AND_ONE = (1..ListPage.SIZE + 1).collect { new Row("k" + it, "Name " + it, it as Long) }

    static final KeysetStatements<SampleColumn> TIED_BY_KEY_STATEMENTS =
            declaredOn(SampleColumn.KEY, RELATION, FILTERED, EVERY_EXPRESSION, [:])

    /** Its ties broken by size, so a page cut by these statements cannot be told from one cut by the key. */
    static final KeysetStatements<SampleColumn> TIED_BY_SIZE_STATEMENTS =
            declaredOn(SampleColumn.SIZE, RELATION, FILTERED, EVERY_EXPRESSION, [:])

    /** A relation binding a value of its own, the same whatever a reader asks. */
    static final String BINDING_RELATION = "select * from t where t.role = any (:held)"

    /** A relation read within whatever the query is read within. */
    static final String SCOPED_RELATION = "select * from t where t.scope = " + KeysetStatements.WITHIN

    /** Declared afresh for every feature, so what the declaration writes is written again each time it is asked about. */
    KeysetStatements<SampleColumn> statements = declaredOn(SampleColumn.KEY, RELATION, FILTERED, EVERY_EXPRESSION, [:])

    /** The list's name, its tie-break and its opening order, each refused as the list's own shape refuses it. */
    def "statements cannot be declared without a list, a relation, a filter condition, the expressions sorted by or what the relation binds"() {
        when:
        new KeysetStatements(name, tiebreak, opening, relation, filtered, expressions, bound)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        name     | tiebreak         | opening | relation | filtered | expressions      | bound || message
        null     | SampleColumn.KEY | BY_KEY  | RELATION | FILTERED | EVERY_EXPRESSION | [:]   || "ListShape name must not be null"
        "listed" | null             | BY_KEY  | RELATION | FILTERED | EVERY_EXPRESSION | [:]   || "ListShape tiebreak must not be null"
        "listed" | SampleColumn.KEY | null    | RELATION | FILTERED | EVERY_EXPRESSION | [:]   || "ListShape opening order must not be null"
        "listed" | SampleColumn.KEY | BY_KEY  | null     | FILTERED | EVERY_EXPRESSION | [:]   || "KeysetStatements relation must not be null"
        "listed" | SampleColumn.KEY | BY_KEY  | RELATION | null     | EVERY_EXPRESSION | [:]   || "KeysetStatements filter condition must not be null"
        "listed" | SampleColumn.KEY | BY_KEY  | RELATION | FILTERED | null             | [:]   || "KeysetStatements expressions must not be null"
        "listed" | SampleColumn.KEY | BY_KEY  | RELATION | FILTERED | EVERY_EXPRESSION | null  || "KeysetStatements bound parameters must not be null"
    }

    /**
     * What the relation binds is in no cursor, so a shape two declarations shared would let either's cursors
     * page through the other: each declaration makes its own, and hands it out as declared.
     */
    def "every declaration makes the shape of its own list, which no other declaration holds"() {
        given:
        def other = declaredOn(SampleColumn.KEY, BINDING_RELATION, FILTERED, EVERY_EXPRESSION, [held: ["owner"] as String[]])

        expect:
        statements.shape().name() == "listed"
        statements.shape().tiebreak() == SampleColumn.KEY
        statements.shape().order(null) == BY_KEY

        and: "asked twice, the one shape; asked of another declaration, never it"
        statements.shape().is(statements.shape())
        !other.shape().is(statements.shape())
    }

    /** Bound under a name a reader's query binds too, one of the two would silently be the other. */
    def "a relation binding a parameter a reader's query binds is refused"() {
        when:
        declaredOn(SampleColumn.KEY, BINDING_RELATION, FILTERED, EVERY_EXPRESSION, [(name): "anything"])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "KeysetStatements binds a parameter a reader's query binds"

        where:
        name << ["filter", "within", "afterValue", "afterTie", "limit"]
    }

    /** Each column the list sorts by is one expression, never none and never a choice of two. */
    def "statements are refused where a column is declared twice or not at all"() {
        when:
        declaredOn(SampleColumn.KEY, RELATION, FILTERED, expressions, [:])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        expressions                                                        || message
        [KEY_EXPRESSION, NAME_EXPRESSION, SIZE_EXPRESSION, NAME_EXPRESSION] || "KeysetStatements declares one column twice"
        [KEY_EXPRESSION, NAME_EXPRESSION]                                  || "KeysetStatements declares no expression for a column the list sorts by"
        []                                                                 || "KeysetStatements declares no expression for a column the list sorts by"
    }

    /** Bound only where the relation reads it, the scope named by the filter alone would fail at the store. */
    def "a filter condition reading a scope is refused unless the relation is read within one"() {
        when:
        declaredOn(SampleColumn.KEY, RELATION, FILTERED + " and t.scope = " + KeysetStatements.WITHIN, EVERY_EXPRESSION, [:])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "KeysetStatements filter condition reads a scope its relation is not read within"
    }

    def "a filter condition reading the scope its relation is read within is bound it wherever the filter is"() {
        given:
        def within = declaredOn(SampleColumn.KEY, SCOPED_RELATION, FILTERED + " and t.scope = " + KeysetStatements.WITHIN,
                EVERY_EXPRESSION, [:])
        def query = new ListQuery<>("g-7", BY_KEY, GRACE)

        expect:
        (within.statement(query, null) =~ /:(\w+)/).collect { it[1] }.toSet() == within.parameters(query, null).keySet()
        within.parameters(query, null).within == "g-7"
    }

    /**
     * The tie-breaking column is compared on its own when it is what the order is by, and after the
     * column sorted by otherwise, always ascending. A row holding nothing is after every row holding
     * something, which is the condition's first term, and a page resumed after a row holding nothing
     * reads on among the rows holding nothing alone.
     */
    def "each way of reading a list is the one statement written out for it"() {
        expect:
        statements.statement(new ListQuery<>("", order, filter), after) == statement

        where:
        order              | filter | after          || statement
        BY_KEY             | null   | null           || RELATION + " order by " + KEY + " asc limit :limit"
        BY_KEY_DESCENDING  | GRACE  | AFTER_KEY      || RELATION + " and (" + FILTERED + ") and " + KEY + " < :afterTie order by " + KEY + " desc limit :limit"
        BY_KEY             | null   | AFTER_KEY      || RELATION + " and " + KEY + " > :afterTie order by " + KEY + " asc limit :limit"
        BY_NAME            | null   | null           || RELATION + " order by " + NAME + " asc nulls last, " + KEY + " asc limit :limit"
        BY_NAME_DESCENDING | null   | AFTER_NAME     || RELATION + " and ((t.name) is null or " + NAME + " < :afterValue or (" + NAME + " = :afterValue and " + KEY + " > :afterTie)) order by " + NAME + " desc nulls last, " + KEY + " asc limit :limit"
        BY_NAME            | GRACE  | AFTER_NAME     || RELATION + " and (" + FILTERED + ") and ((t.name) is null or " + NAME + " > :afterValue or (" + NAME + " = :afterValue and " + KEY + " > :afterTie)) order by " + NAME + " asc nulls last, " + KEY + " asc limit :limit"
        BY_NAME            | GRACE  | AFTER_NAMELESS || RELATION + " and (" + FILTERED + ") and (t.name) is null and " + KEY + " > :afterTie order by " + NAME + " asc nulls last, " + KEY + " asc limit :limit"
        BY_NAME_DESCENDING | null   | AFTER_NAMELESS || RELATION + " and (t.name) is null and " + KEY + " > :afterTie order by " + NAME + " desc nulls last, " + KEY + " asc limit :limit"
        BY_SIZE            | null   | AFTER_SIZE     || RELATION + " and (t.size > :afterValue or (t.size = :afterValue and " + KEY + " > :afterTie)) order by t.size asc, " + KEY + " asc limit :limit"
        BY_SIZE_DESCENDING | GRACE  | null           || RELATION + " and (" + FILTERED + ") order by t.size desc, " + KEY + " asc limit :limit"
        BY_SIZE_DESCENDING | null   | AFTER_SIZE     || RELATION + " and (t.size < :afterValue or (t.size = :afterValue and " + KEY + " > :afterTie)) order by t.size desc, " + KEY + " asc limit :limit"
    }

    /** Sorted by a column no position can hold as it is, so the position's value is written back into it. */
    def "a position's value is compared as the expression's position form writes it, while the order and the tie are not"() {
        given:
        def converted = declaredOn(SampleColumn.KEY, RELATION, FILTERED,
                [KEY_EXPRESSION, NAME_EXPRESSION, new SortExpression<>(SampleColumn.SIZE, "t.at", null, "seconds(%s)")], [:])

        expect:
        converted.statement(new ListQuery<>("", BY_SIZE_DESCENDING, null), AFTER_SIZE) == RELATION +
                " and (t.at < seconds(:afterValue) or (t.at = seconds(:afterValue) and " + KEY + " > :afterTie))" +
                " order by t.at desc, " + KEY + " asc limit :limit"
    }

    /** Asked for twice, a statement is the very string written when the list was declared, and no two readings share one. */
    def "every statement is written once, when the list is declared, and each way of reading has its own"() {
        when:
        def written = EVERY_READING.collect { reading ->
            statements.statement(new ListQuery<>("", reading[0] as ListOrder, reading[1] as ListFilter), reading[2] as ListPosition)
        }
        def again = EVERY_READING.collect { reading ->
            statements.statement(new ListQuery<>("", reading[0] as ListOrder, reading[1] as ListFilter), reading[2] as ListPosition)
        }

        then:
        EVERY_READING.size() == 28
        [written, again].transpose().every { first, second -> first.is(second) }

        and: "and no two ways of reading are answered by one statement"
        written.toSet().size() == EVERY_READING.size()
    }

    def "a position holding nothing where every row holds something is refused rather than read"() {
        when:
        statements.statement(new ListQuery<>("", order, null), new ListPosition(null, "k150"))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "KeysetStatements position holds nothing where every row holds something"

        where:
        order << [BY_SIZE, BY_SIZE_DESCENDING]
    }

    /** One beyond the page is asked for, which is how whether anything follows is learnt without a count. */
    def "what was typed, where the page resumes, and one more than a page are bound, and nothing else is"() {
        expect:
        statements.parameters(new ListQuery<>("", order, filter), after) == bound

        where:
        order   | filter | after          || bound
        BY_KEY  | null   | null           || [limit: ListPage.SIZE + 1]
        BY_KEY  | GRACE  | AFTER_KEY      || [filter: "grace", afterTie: "k150", limit: ListPage.SIZE + 1]
        BY_NAME | null   | AFTER_NAME     || [afterValue: "grace hopper", afterTie: "k150", limit: ListPage.SIZE + 1]
        BY_NAME | GRACE  | AFTER_NAMELESS || [filter: "grace", afterTie: "k150", limit: ListPage.SIZE + 1]
        BY_SIZE | null   | AFTER_SIZE     || [afterValue: 2L, afterTie: "k150", limit: ListPage.SIZE + 1]
    }

    /**
     * Held level across every way of reading: a parameter a statement names and nobody binds fails
     * the statement, and one bound that it never names means the statement is not the one it was meant
     * to be.
     */
    def "every parameter a statement names is bound, and nothing it does not name is"() {
        given:
        def query = new ListQuery<>("", order as ListOrder, filter as ListFilter)

        expect:
        (statements.statement(query, after as ListPosition) =~ /:(\w+)/).collect { it[1] }.toSet() ==
                statements.parameters(query, after as ListPosition).keySet()

        where:
        [order, filter, after] << EVERY_READING
    }

    /**
     * What the relation binds is bound to every statement beside what the reader asked, and held as it
     * was declared: a declaration changed afterwards changes no statement already written.
     */
    def "what the list is declared to bind is bound to every way of reading it, beside what the reader asked"() {
        given:
        def held = ["owner"] as String[]
        def declared = [held: held]
        def binding = declaredOn(SampleColumn.KEY, BINDING_RELATION, FILTERED, EVERY_EXPRESSION, declared)
        def query = new ListQuery<>("", order as ListOrder, filter as ListFilter)

        when:
        declared.put("stray", "value")
        def parameters = binding.parameters(query, after as ListPosition)

        then:
        parameters.held.is(held)
        parameters.keySet() - "held" == statements.parameters(query, after as ListPosition).keySet()

        and: "each of them named by the statement, so what was bound is what it reads"
        (binding.statement(query, after as ListPosition) =~ /:(\w+)/).collect { it[1] }.toSet() == parameters.keySet()

        where:
        [order, filter, after] << EVERY_READING
    }

    /**
     * What a list is read within is the very value its cursors are bound to, so a cursor minted within one
     * scope cannot page through another: nothing but the query carries it.
     */
    def "a list read within a scope binds the query's own scope to every way of reading it, beside what the reader asked"() {
        given:
        def within = declaredOn(SampleColumn.KEY, SCOPED_RELATION, FILTERED, EVERY_EXPRESSION, [:])
        def query = new ListQuery<>("g-7", order as ListOrder, filter as ListFilter)

        when:
        def parameters = within.parameters(query, after as ListPosition)

        then:
        parameters.within == "g-7"
        parameters.keySet() - "within" == statements.parameters(
                new ListQuery<>("", order as ListOrder, filter as ListFilter), after as ListPosition).keySet()

        and: "each of them named by the statement, so what was bound is what it reads"
        (within.statement(query, after as ListPosition) =~ /:(\w+)/).collect { it[1] }.toSet() == parameters.keySet()

        where:
        [order, filter, after] << EVERY_READING
    }

    /** A scope and a statement disagreeing about whether there is one is a query asked of the wrong list. */
    def "a query within a scope is refused by a list read whole, and one within none by a list read within one"() {
        given:
        def declared = declaredOn(SampleColumn.KEY, relation, FILTERED, EVERY_EXPRESSION, [:])

        when:
        declared.parameters(new ListQuery<>(scope, BY_KEY, null), null)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == message

        where:
        relation        | scope || message
        RELATION        | "g-7" || "KeysetStatements list read whole was asked for within a scope"
        SCOPED_RELATION | ""    || "KeysetStatements list read within a scope was asked for within none"
    }

    /** Named as a word and not as the start of a longer one, so a parameter merely beginning alike is not it. */
    def "a relation naming only a parameter that begins like the scope's is a list read whole"() {
        given:
        def declared = declaredOn(SampleColumn.KEY, "select * from t where t.x = :withinReach", FILTERED,
                EVERY_EXPRESSION, [withinReach: "x"])

        expect:
        declared.parameters(new ListQuery<>("", BY_KEY, null), null) == [withinReach: "x", limit: ListPage.SIZE + 1]
    }

    /** Bound only where the condition names it, so no statement is handed a value it never reads. */
    def "a filter condition matching names is bound what was typed spaced as names are, beside it as typed"() {
        given:
        def noBreak = String.valueOf((char) 0xA0)
        def typed = "grace" + noBreak + " hopper"

        when:
        def parameters = declaredOn(SampleColumn.KEY, RELATION, filtered, EVERY_EXPRESSION, [:])
                .parameters(new ListQuery<>("", BY_KEY, new ListFilter(typed)), null)

        then:
        parameters.filter == typed
        parameters.containsKey("filterSpaced") == (spaced != null)
        parameters.filterSpaced == spaced

        where:
        filtered                                         || spaced
        "f(t.name, :filterSpaced) or f(t.key, :filter)"  || "grace hopper"
        FILTERED                                         || null
    }

    /** The position a page ends on is placed by the tie-breaking column of the list these statements were declared for. */
    def "what a statement fetched is cut into a page of the list the statements were declared for"() {
        when:
        def page = declared.page(PAGE_AND_ONE, new ListQuery<>("", BY_NAME, null))

        then:
        page.rows() == PAGE_AND_ONE.take(ListPage.SIZE)
        page.next() == next

        where:
        declared                || next
        TIED_BY_KEY_STATEMENTS  || new ListPosition("Name " + ListPage.SIZE, "k" + ListPage.SIZE)
        TIED_BY_SIZE_STATEMENTS || new ListPosition("Name " + ListPage.SIZE, ListPage.SIZE as Long)
    }

    /** Declared as the one list every feature here reads, opening on the key, its ties broken as asked. */
    static KeysetStatements<SampleColumn> declaredOn(
            SampleColumn tiebreak, String relation, String filtered, List expressions, Map bound) {
        new KeysetStatements<>("listed", tiebreak, BY_KEY, relation, filtered, expressions, bound)
    }
}
