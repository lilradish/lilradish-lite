package org.lilradish.lite.domain.listing

import org.lilradish.lite.testutil.listing.SampleColumn
import spock.lang.Specification

class ListQuerySpec extends Specification {

    static final ListOrder<SampleColumn> BY_KEY = new ListOrder<>(SampleColumn.KEY, false)

    /** A list read whole is read within the empty scope, which is a scope and not the absence of one. */
    def "a query cannot be taken without what it is read within or without an order"() {
        when:
        new ListQuery(scope, order, new ListFilter("grace"))

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        scope | order  || message
        null  | BY_KEY || "ListQuery scope must not be null"
        ""    | null   || "ListQuery order must not be null"
    }
}
