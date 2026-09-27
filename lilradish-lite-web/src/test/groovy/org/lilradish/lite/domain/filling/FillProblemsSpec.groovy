package org.lilradish.lite.domain.filling

import org.lilradish.lite.domain.declaration.FieldName
import spock.lang.Specification

class FillProblemsSpec extends Specification {

    static final FillProblem MISSING =
            new FillProblem(new FillPath([new FillPath.Named(new FieldName("complaint"))]), FillReason.MISSING)

    def "problems are held in the order found, apart from the list they were gathered in, beside how many were found"() {
        given:
        def gathered = [MISSING]

        when:
        def problems = new FillProblems(gathered, 3)
        gathered.clear()

        then:
        problems.problems() == [MISSING]
        problems.found() == 3
    }

    def "no problems at all is refused, since values that all fit are kept instead"() {
        when:
        new FillProblems([], 0)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FillProblems names at least one problem"
    }

    def "fewer found than are named is refused"() {
        when:
        new FillProblems([MISSING, MISSING], 1)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FillProblems found no fewer than it names: 1"
    }
}
