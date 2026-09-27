package org.lilradish.lite.domain.listing

import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

class ListOrderSpec extends Specification {

    def "an order cannot be built without a column"() {
        when:
        new ListOrder(null, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ListOrder column must not be null"
    }

    def "an order is spelt as the column's published name, led by a hyphen exactly where it descends"() {
        expect:
        new ListOrder(column, descending).published() == spelt

        where:
        column            | descending || spelt
        SampleColumn.KEY  | false      || "key"
        SampleColumn.KEY  | true       || "-key"
        SampleColumn.NAME | false      || "name"
        SampleColumn.NAME | true       || "-name"
        SampleColumn.SIZE | false      || "size"
        SampleColumn.SIZE | true       || "-size"
    }
}
