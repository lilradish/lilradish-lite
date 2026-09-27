package org.lilradish.lite.domain.listing

import spock.lang.Specification

class ListPositionSpec extends Specification {

    /** The tie-breaking value is what places a row among its ties, so a position without one places no row at all. */
    def "a position cannot be taken without the value that breaks every tie"() {
        when:
        new ListPosition("Ada Lovelace", null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ListPosition tiebreakValue must not be null"
    }
}
