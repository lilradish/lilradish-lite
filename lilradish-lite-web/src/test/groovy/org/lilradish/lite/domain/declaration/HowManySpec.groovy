package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class HowManySpec extends Specification {

    /** Many holding at most one is still many, and a value of it is a list; none is what is not chosen yet. */
    def "many holds as many as it says, from one up, or says nothing yet"() {
        expect:
        new HowMany.Many(most).most() == most

        and: "and it is never one, however few it may hold"
        new HowMany.Many(most) != new HowMany.One()

        where:
        most << [1, 3, Integer.MAX_VALUE, null]
    }

    def "many said to hold fewer than one is refused"() {
        when:
        new HowMany.Many(most)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "HowMany.Many most must be at least one: " + most

        where:
        most << [0, -3]
    }
}
