package org.lilradish.lite.app.library

import spock.lang.Specification

class RunsKindSpec extends Specification {

    /** A page names what a step runs by these both ways, so a spelling moved here is every page's broken. */
    def "each thing a step may run is published under the spelling a page names it by"() {
        expect:
        RunsKind.values().collect { [it, it.published()] } == [
                [RunsKind.QUESTION, "question"],
                [RunsKind.WORKFLOW, "workflow"],
                [RunsKind.CODE_STEP, "code_step"],
                [RunsKind.ROUTE, "route"],
        ]
    }

    def "a spelling is read as the kind it spells, and nothing else as any"() {
        expect:
        RunsKind.spelt(spelling) == kind

        where:
        spelling    || kind
        "question"  || RunsKind.QUESTION
        "code_step" || RunsKind.CODE_STEP
        "route"     || RunsKind.ROUTE
        "workflow"  || RunsKind.WORKFLOW
        "Question"  || null
        "entry"     || null
    }
}
