package org.lilradish.lite.domain.run

import spock.lang.Specification

class StepFailureSpec extends Specification {

    def "tries are spent once at least as many were used as were declared, one declared at the least"() {
        when:
        new StepFailure.TriesSpent(used, declared)

        then:
        noExceptionThrown()

        where:
        used | declared
        1    | 1
        3    | 3
        3    | 2
    }

    def "tries not spent are refused as spent, naming how many of how many"() {
        when:
        new StepFailure.TriesSpent(used, declared)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepFailure.TriesSpent used " + used + " of " + declared + " is not spent"

        where:
        used | declared
        1    | 2
        0    | 1
        0    | 0
        1    | 0
        -1   | -1
    }
}
