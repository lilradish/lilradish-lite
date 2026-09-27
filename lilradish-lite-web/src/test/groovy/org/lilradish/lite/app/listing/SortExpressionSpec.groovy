package org.lilradish.lite.app.listing

import org.lilradish.lite.domain.listing.ListColumn
import org.lilradish.lite.domain.listing.ListValueKind
import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

class SortExpressionSpec extends Specification {

    /** A column holding a fact that holds or does not, which no collation applies to either. */
    enum Flagged implements ListColumn {
        FLAG

        @Override
        String published() {
            "flag"
        }

        @Override
        ListValueKind kind() {
            ListValueKind.BOOLEAN
        }
    }

    /**
     * Text left to the database's default collation orders one way on one server and another way on
     * the next, and a collation named for a count names a comparison that does not exist.
     */
    def "an expression that is missing, or that names its collation where it must not or fails to where it must, is refused"() {
        when:
        new SortExpression(column, expression, collation)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        column      | expression   | collation   || expected                 | message
        null        | "t.key"      | "ucs_basic" || NullPointerException     | "SortExpression column must not be null"
        SampleColumn.KEY  | null         | "ucs_basic" || NullPointerException     | "SortExpression expression must not be null"
        SampleColumn.KEY  | " "          | "ucs_basic" || IllegalArgumentException | "SortExpression expression must not be blank"
        SampleColumn.KEY  | "t.key"      | null        || IllegalArgumentException | "SortExpression over text must name its collation"
        SampleColumn.NAME | "t.name"     | null        || IllegalArgumentException | "SortExpression over text must name its collation"
        SampleColumn.NAME | "t.name"     | " "         || IllegalArgumentException | "SortExpression over text must name its collation"
        SampleColumn.SIZE | "t.size"     | "ucs_basic" || IllegalArgumentException | "SortExpression over anything but text must name no collation"
        Flagged.FLAG      | "t.flag"     | "ucs_basic" || IllegalArgumentException | "SortExpression over anything but text must name no collation"
    }

    /** Named nowhere, a position would be compared with nothing it holds; named twice, bound twice over. */
    def "a position form that is missing, or names its parameter other than once, is refused"() {
        when:
        new SortExpression(SampleColumn.SIZE, "t.at", null, positionAs)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        positionAs          || expected                 | message
        null                || NullPointerException     | "SortExpression position form must not be null"
        "seconds(t)"        || IllegalArgumentException | "SortExpression position form must name its parameter once"
        "range(%s, %s)"     || IllegalArgumentException | "SortExpression position form must name its parameter once"
    }

    /** Bracketed, so a collation named after an expression of several terms applies to all of them. */
    def "an expression is compared under its collation where it names one, and as itself where it names none"() {
        expect:
        new SortExpression(column, expression, collation).compared() == compared

        where:
        column      | expression            | collation   || compared
        SampleColumn.KEY  | "t.key"               | "ucs_basic" || "(t.key) collate ucs_basic"
        SampleColumn.NAME | "t.first || t.last"   | '"unicode"' || '(t.first || t.last) collate "unicode"'
        SampleColumn.SIZE | "t.size"              | null        || "t.size"
        Flagged.FLAG      | "t.flag"              | null        || "t.flag"
    }

    /** Written in whole, so a form holding a percent sign of its own is no format string. */
    def "a position's parameter is written into the position form where one is named, and as itself where none is"() {
        expect:
        expression.positioned(":afterValue") == positioned

        where:
        expression                                                                    || positioned
        new SortExpression(SampleColumn.SIZE, "t.at", null, "seconds(%s) + 100%%")    || "seconds(:afterValue) + 100%%"
        new SortExpression(SampleColumn.SIZE, "t.size", null)                         || ":afterValue"
    }
}
