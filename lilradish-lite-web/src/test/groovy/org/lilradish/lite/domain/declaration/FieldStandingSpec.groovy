package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class FieldStandingSpec extends Specification {

    /** Pinned whole and in order: the store's vocabulary is held to this declaration label by label. */
    def "what lets a value stand is one of three, in the order they are declared"() {
        expect:
        FieldStanding.values().toList() == [FieldStanding.ALWAYS, FieldStanding.NEVER, FieldStanding.ABOVE_CONFIDENCE]
    }

    def "each is published under the spelling a reader names it by"() {
        expect:
        FieldStanding.values().collectEntries { [(it): it.published()] } == [
                (FieldStanding.ALWAYS)          : "always",
                (FieldStanding.NEVER)           : "never",
                (FieldStanding.ABOVE_CONFIDENCE): "above_confidence",
        ]
    }
}
