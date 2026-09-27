package org.lilradish.lite.domain.run

import spock.lang.Specification

class CeilingChangeIdSpec extends Specification {

    def "keeps the identity it was constructed with, two naming one change being one"() {
        given:
        def value = UUID.fromString("00000009-0000-4000-8000-000000000001")

        when:
        def identifier = new CeilingChangeId(value)

        then:
        identifier.value() == value
        identifier == new CeilingChangeId(UUID.fromString("00000009-0000-4000-8000-000000000001"))
        identifier != new CeilingChangeId(UUID.fromString("00000009-0000-4000-8000-000000000002"))
    }

    def "refuses to stand for no change at all"() {
        when:
        new CeilingChangeId(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "CeilingChangeId must not be null"
    }
}
