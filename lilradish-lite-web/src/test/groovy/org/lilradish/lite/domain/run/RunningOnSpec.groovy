package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.RunningOn.CALL
import static org.lilradish.lite.domain.run.RunningOn.CODE
import static org.lilradish.lite.domain.run.RunningOn.NEXT_TRY

import spock.lang.Specification

class RunningOnSpec extends Specification {

    def "what a running step has out is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        RunningOn.values().collect { [it, it.published()] } == [[CODE, "code"], [CALL, "call"], [NEXT_TRY, "next_try"]]
    }
}
