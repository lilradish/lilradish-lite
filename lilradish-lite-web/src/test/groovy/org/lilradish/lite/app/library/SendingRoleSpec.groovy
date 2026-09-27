package org.lilradish.lite.app.library

import spock.lang.Specification

class SendingRoleSpec extends Specification {

    /** A page names what a step asks a model to do by these, so a spelling moved here is every page's broken. */
    def "each thing a step asks a model to do is published under the spelling a page names it by"() {
        expect:
        SendingRole.values().collect { [it, it.published()] } == [
                [SendingRole.PRODUCING, "producing"],
                [SendingRole.REVIEWING, "reviewing"],
        ]
    }
}
