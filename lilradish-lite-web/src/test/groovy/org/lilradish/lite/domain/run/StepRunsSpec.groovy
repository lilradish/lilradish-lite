package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.gives
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.pinned
import static org.lilradish.lite.domain.run.fixture.Runs.standing
import static org.lilradish.lite.domain.run.fixture.Runs.takes

import org.lilradish.lite.domain.declaration.Instruction
import spock.lang.Specification

class StepRunsSpec extends Specification {

    def "a question is refused where it names other than one key per field it gives back"() {
        when:
        new StepRuns.Question(pinned(1), new Instruction("Answer what is asked."), takes([]),
                gives([standing("first"), standing("second")]), (0..<keys).collect { key(300 + it) }, [:])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "StepRuns.Question names " + keys + " keys for the 2 fields it gives back"

        where:
        keys << [0, 1, 3]
    }
}
