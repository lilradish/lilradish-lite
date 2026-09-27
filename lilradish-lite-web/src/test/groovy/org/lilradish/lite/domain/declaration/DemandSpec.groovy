package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class DemandSpec extends Specification {

    def "every field says whether it must be given, either way, whether or not it stands"() {
        expect:
        demand.mustBe() == mustBe

        where:
        demand                                                 || mustBe
        new Demand.Given(true)                                 || true
        new Demand.Given(false)                                || false
        new Demand.Stands(true, FieldStanding.NEVER, null)     || true
        new Demand.Stands(false, FieldStanding.NEVER, null)    || false
    }

    /** A floor is a whole percent from 1 to 100, and only standing above a confidence has one. */
    def "a value stands as it says, a floor only above a confidence, or says nothing yet"() {
        when:
        def stands = new Demand.Stands(true, standing, floor)

        then:
        stands.standing() == standing
        stands.floor() == floor

        where:
        standing                       | floor
        FieldStanding.ALWAYS           | null
        FieldStanding.NEVER            | null
        FieldStanding.ABOVE_CONFIDENCE | 1
        FieldStanding.ABOVE_CONFIDENCE | 80
        FieldStanding.ABOVE_CONFIDENCE | 100
        FieldStanding.ABOVE_CONFIDENCE | null
        null                           | null
    }

    def "a floor beside any other standing, or none, is refused"() {
        when:
        new Demand.Stands(true, standing, 80)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Demand.Stands carries a floor only above a confidence"

        where:
        standing << [FieldStanding.ALWAYS, FieldStanding.NEVER, null]
    }

    def "a floor outside 1 to 100 is refused"() {
        when:
        new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, floor)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Demand.Stands floor must be from 1 to 100: " + floor

        where:
        floor << [0, 101, -1]
    }
}
