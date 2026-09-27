package org.lilradish.lite.domain.registry

import spock.lang.Specification

class EntryIdSpec extends Specification {

    def "keeps the identity it was constructed with, two naming one entry being one"() {
        given:
        def value = UUID.fromString("00000006-0000-4000-8000-000000000001")

        when:
        def identifier = new EntryId(value)

        then:
        identifier.value() == value
        identifier == new EntryId(UUID.fromString("00000006-0000-4000-8000-000000000001"))
        identifier != new EntryId(UUID.fromString("00000006-0000-4000-8000-000000000002"))
    }

    def "refuses to stand for no entry at all"() {
        when:
        new EntryId(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "EntryId must not be null"
    }
}
