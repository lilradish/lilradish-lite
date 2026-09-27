package org.lilradish.lite.app.inference

import spock.lang.Specification

class ReasoningEffortSpec extends Specification {

    /**
     * The endpoint's own list, in its order, each beside the constant a deployment names it by: a
     * constant added, dropped, respelt or bound to another's spelling fails here.
     */
    def "every effort a deployment may name is sent as the endpoint spells it, and no other is held"() {
        expect:
        ReasoningEffort.values().collect { [it.name(), it.sent()] } == [
            ["NONE", "none"],
            ["MINIMAL", "minimal"],
            ["LOW", "low"],
            ["MEDIUM", "medium"],
            ["HIGH", "high"],
            ["XHIGH", "xhigh"],
            ["MAX", "max"],
        ]
    }
}
