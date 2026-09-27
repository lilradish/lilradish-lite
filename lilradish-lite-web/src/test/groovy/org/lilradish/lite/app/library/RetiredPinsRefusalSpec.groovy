package org.lilradish.lite.app.library

import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class RetiredPinsRefusalSpec extends Specification {

    static final RetiredPinsRefusal.RetiredPin PIN = new RetiredPinsRefusal.RetiredPin(
            new EntryId(UUID.fromString("00000006-0000-4000-8000-000000000001")),
            EntryKind.REFERENCE_LIST,
            new EntryName("Regions"),
            new RetiredPinsRefusal.NumberedVersion(
                    new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000001")), 1),
            new RetiredPinsRefusal.NumberedVersion(
                    new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000002")), 2))

    /** The pins travel beside a sentence that names none of them. */
    def "a refusal for pins retired since carries each pin, under the one sentence written for it"() {
        given:
        def pins = [PIN]

        when:
        def refusal = new RetiredPinsRefusal(pins)
        pins.clear()

        then:
        refusal.errorCode() == RefusalCode.VERSION_PINS_RETIRED
        refusal.message == "This version pins a version retired since."
        !refusal.message.contains("Regions")
        refusal.pins() == [PIN]
    }

    def "a refusal for pins retired since cannot be made of no list, and its pins are nobody's to change"() {
        when:
        new RetiredPinsRefusal(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "RetiredPinsRefusal pins must not be null"

        when:
        new RetiredPinsRefusal([PIN]).pins().add(PIN)

        then:
        thrown(UnsupportedOperationException)
    }
}
