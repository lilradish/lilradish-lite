package org.lilradish.lite.domain.inference

import spock.lang.Specification

class TurnAwaySpec extends Specification {

    def "keeps what the model said, or that it said nothing, and whether it was used up"() {
        when:
        def turnAway = new TurnAway(said, spentUp)

        then:
        turnAway.said() == said
        turnAway.spentUp() == spentUp

        where:
        said                    | spentUp
        null                    | false
        "overloaded"            | false
        "spend limit reached"   | true
        " "                     | true
    }

    def "refuses to say nothing in two ways, keeping absent as the only one"() {
        when:
        new TurnAway("", false)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "TurnAway said is empty; nothing said is absent"
    }
}
