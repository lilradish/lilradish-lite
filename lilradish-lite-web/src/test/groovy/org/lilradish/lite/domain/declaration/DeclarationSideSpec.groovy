package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class DeclarationSideSpec extends Specification {

    /** Pinned whole and in order: the store's vocabulary is held to this declaration label by label. */
    def "a declaration has two halves, in the order they are declared"() {
        expect:
        DeclarationSide.values().toList() == [DeclarationSide.TAKES, DeclarationSide.GIVES]
    }

    /** Each half of a version is addressed under this spelling, so a spelling moved is every such address broken. */
    def "each half is published under the spelling it is addressed by"() {
        expect:
        DeclarationSide.values().collectEntries { [(it): it.published()] } == [
                (DeclarationSide.TAKES): "takes",
                (DeclarationSide.GIVES): "gives",
        ]
    }
}
