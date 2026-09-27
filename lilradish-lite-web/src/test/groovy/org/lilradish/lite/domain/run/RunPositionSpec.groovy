package org.lilradish.lite.domain.run

import spock.lang.Specification

class RunPositionSpec extends Specification {

    def "a run stands nowhere without saying how a raise waits, none waiting being said outright"() {
        when:
        new RunPosition(true, false, false, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "RunPosition raise must not be null"
    }
}
