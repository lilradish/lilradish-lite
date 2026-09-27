package org.lilradish.lite.domain.workflow

import spock.lang.Specification

class StepKindSpec extends Specification {

    /** The store's vocabulary is held to this declaration label by label, so a kind moved here is moved there too. */
    def "what a step may run is told apart three ways, in the order the store declares them"() {
        expect:
        StepKind.values().toList() == [StepKind.ENTRY, StepKind.CODE_STEP, StepKind.ROUTE]
    }
}
