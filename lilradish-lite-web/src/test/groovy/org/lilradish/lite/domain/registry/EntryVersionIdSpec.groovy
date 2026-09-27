package org.lilradish.lite.domain.registry

import spock.lang.Specification

class EntryVersionIdSpec extends Specification {

    def "keeps the identity it was constructed with, two naming one version being one"() {
        given:
        def value = UUID.fromString("00000007-0000-4000-8000-000000000001")

        when:
        def identifier = new EntryVersionId(value)

        then:
        identifier.value() == value
        identifier == new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000001"))
        identifier != new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000002"))
    }

    def "refuses to stand for no version at all"() {
        when:
        new EntryVersionId(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "EntryVersionId must not be null"
    }
}
