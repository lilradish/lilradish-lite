package org.lilradish.lite.domain.workflow

import spock.lang.Specification

class StepProducerSpec extends Specification {

    /** A page names who produces by these, so a constant renamed without its spelling staying put breaks it. */
    def "each producer is published under the spelling a reader names it by, in the order the store declares"() {
        expect:
        StepProducer.values().collect { [it, it.published()] } == [
                [StepProducer.MODEL, "model"],
                [StepProducer.PERSON, "person"],
                [StepProducer.CODE, "code"],
        ]
    }
}
