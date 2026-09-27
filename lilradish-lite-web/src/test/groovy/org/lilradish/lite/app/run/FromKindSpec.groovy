package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.FromKind.CONSTANT
import static org.lilradish.lite.app.run.FromKind.RUN_INPUT
import static org.lilradish.lite.app.run.FromKind.STEP

import spock.lang.Specification

class FromKindSpec extends Specification {

    def "each place an input comes from is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        FromKind.values().collect { [it, it.published()] } == [[RUN_INPUT, "run_input"], [STEP, "step"],
                                                               [CONSTANT, "constant"]]
    }
}
