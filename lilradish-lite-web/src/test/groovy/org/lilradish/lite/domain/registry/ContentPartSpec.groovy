package org.lilradish.lite.domain.registry

import spock.lang.Specification

class ContentPartSpec extends Specification {

    /** A page names where a problem is by these, so a constant renamed without its spelling staying put breaks it. */
    def "each part of a version's content is published under the spelling a reader names it by"() {
        expect:
        ContentPart.values().collectEntries { [(it): it.published()] } == [
                (ContentPart.INSTRUCTION): "instruction",
                (ContentPart.TAKES)      : "takes",
                (ContentPart.GIVES)      : "gives",
                (ContentPart.STEPS)      : "steps",
                (ContentPart.HELPER)     : "helper",
                (ContentPart.TERMS)      : "terms",
                (ContentPart.ASKING)     : "asking",
        ]
    }
}
