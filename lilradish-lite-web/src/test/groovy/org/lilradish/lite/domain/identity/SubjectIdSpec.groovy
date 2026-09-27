package org.lilradish.lite.domain.identity

import spock.lang.Specification

class SubjectIdSpec extends Specification {

    def "keeps the identity it was constructed with, what a provider calls the person being nowhere in it"() {
        given:
        def value = UUID.fromString("00000000-0000-4000-8000-000000000001")

        when:
        def identifier = new SubjectId(value)

        then:
        identifier.value() == value

        and: "two of them naming one subject are the same, which is what lets an act be charged to somebody"
        identifier == new SubjectId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
        identifier != new SubjectId(UUID.fromString("00000000-0000-4000-8000-000000000002"))
    }

    def "refuses to stand for nobody at all"() {
        when:
        new SubjectId(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "SubjectId must not be null"
    }
}
