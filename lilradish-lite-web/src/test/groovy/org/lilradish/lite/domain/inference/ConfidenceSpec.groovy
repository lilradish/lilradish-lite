package org.lilradish.lite.domain.inference

import spock.lang.Specification

class ConfidenceSpec extends Specification {

    def "a confidence is a whole percent, from nothing sure to wholly sure"() {
        expect:
        new Confidence(percent).percent() == percent

        where:
        percent << [0, 1, 50, 99, 100]
    }

    def "a confidence outside a whole percent's range is refused"() {
        when:
        new Confidence(percent)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Confidence is a whole percent from 0 to 100, not " + percent

        where:
        percent << [-1, 101, Integer.MIN_VALUE, Integer.MAX_VALUE]
    }
}
