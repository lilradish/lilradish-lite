package org.lilradish.lite.app.run

import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.RunStepSendAttemptId
import spock.lang.Specification

/**
 * A call a stopped system left unended was out, and may have been taken up, unless it was waiting to be sent again
 * after its newest turnaway: only the newest turnaway says, whatever came before it.
 */
class UnendedCallSpec extends Specification {

    def "a call left unended was out unless it waits to be sent again after its newest turnaway"() {
        expect:
        call(turnedAwayBefore, resent).wasOut() == out

        where:
        turnedAwayBefore | resent || out
        false            | false  || true
        true             | true   || true
        true             | false  || false
    }

    def "a call said to be sent again after no turnaway is refused"() {
        when:
        call(false, true)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "UnendedCall was sent again after no turnaway"
    }

    private static UnendedCall call(boolean turnedAwayBefore, boolean resent) {
        new UnendedCall(new ModelCallId(UUID.randomUUID()), ModelCallPurpose.PRODUCE,
                new ProductionId(UUID.randomUUID()), new RunStepSendAttemptId(UUID.randomUUID()), turnedAwayBefore,
                resent)
    }
}
