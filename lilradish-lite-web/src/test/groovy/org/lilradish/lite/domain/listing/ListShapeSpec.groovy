package org.lilradish.lite.domain.listing

import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

class ListShapeSpec extends Specification {

    static final ListOrder<SampleColumn> BY_KEY = new ListOrder<>(SampleColumn.KEY, false)

    static final ListOrder<SampleColumn> BY_SIZE_DESCENDING = new ListOrder<>(SampleColumn.SIZE, true)

    static final ListShape<SampleColumn> OPENING_ON_KEY = new ListShape<>("listed", SampleColumn.KEY, BY_KEY)

    /**
     * A tie can only be broken by a column every row holds something in: two rows holding nothing
     * there are a tie nothing breaks, and a page boundary between them repeats or skips one.
     */
    def "a shape that could not bind a cursor or break a tie cannot be built"() {
        when:
        new ListShape(name, tiebreak, opening)

        then:
        def refused = thrown(expected)
        refused.message == message

        where:
        name     | tiebreak    | opening || expected                 | message
        null     | SampleColumn.KEY  | BY_KEY  || NullPointerException     | "ListShape name must not be null"
        "listed" | null        | BY_KEY  || NullPointerException     | "ListShape tiebreak must not be null"
        "listed" | SampleColumn.KEY  | null    || NullPointerException     | "ListShape opening order must not be null"
        ""       | SampleColumn.KEY  | BY_KEY  || IllegalArgumentException | "ListShape name must not be empty"
        "listed" | SampleColumn.NAME | BY_KEY  || IllegalArgumentException | "ListShape tiebreak must be a column no row leaves empty"
    }

    /** The one order nobody asks for is the list's own, whichever that is, and not some library default. */
    def "a query nobody ordered opens on the order the list declares, whichever column breaks its ties"() {
        expect:
        new ListShape<>("listed", tiebreak, opening).order(null) == opening

        where:
        tiebreak    | opening
        SampleColumn.KEY  | BY_KEY
        SampleColumn.KEY  | BY_SIZE_DESCENDING
        SampleColumn.SIZE | BY_KEY
    }

    def "an order is read as one column, descending where a hyphen leads it"() {
        expect:
        OPENING_ON_KEY.order(requested) == new ListOrder<>(column, descending)

        where:
        requested || column      | descending
        "key"     || SampleColumn.KEY  | false
        "-key"    || SampleColumn.KEY  | true
        "name"    || SampleColumn.NAME | false
        "-name"   || SampleColumn.NAME | true
        "size"    || SampleColumn.SIZE | false
        "-size"   || SampleColumn.SIZE | true
    }

    /**
     * Refused rather than read as whichever column it most resembles: a list of columns is a
     * tie-break the table does not offer, and a spelling differing by case or by a space is a column
     * this reader never named.
     */
    def "anything but one sortable column in one direction is refused rather than guessed at"() {
        when:
        OPENING_ON_KEY.order(requested)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "ListOrder names no single column the list is sorted by"

        where:
        requested << ["", "-", "--key", "+key", "roles", "-roles", "key,name", "-size,key", "Key", "KEY", " key",
                      "key ", "sizes"]
    }

    def "every order is read back from the way it is spelt, so what one query publishes the next can read"() {
        given:
        def order = new ListOrder<>(column, descending)

        expect:
        OPENING_ON_KEY.order(order.published()) == order

        where:
        [column, descending] << [SampleColumn.values() as List, [false, true]].combinations()
    }
}
