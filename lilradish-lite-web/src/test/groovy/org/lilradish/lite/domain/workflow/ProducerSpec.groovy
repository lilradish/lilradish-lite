package org.lilradish.lite.domain.workflow

import org.lilradish.lite.domain.model.ModelName
import spock.lang.Specification

class ProducerSpec extends Specification {

    def "each producer is of the kind the store tells it apart as"() {
        expect:
        producer.kind() == kind

        where:
        producer                                                                  || kind
        new Producer.Model(new ModelChoice(new ModelName("general"), null), true)  || StepProducer.MODEL
        new Producer.Person()                                                     || StepProducer.PERSON
        new Producer.Code()                                                       || StepProducer.CODE
    }

    def "a model producing names its choice"() {
        when:
        new Producer.Model(null, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Producer.Model choice must not be null"
    }
}
