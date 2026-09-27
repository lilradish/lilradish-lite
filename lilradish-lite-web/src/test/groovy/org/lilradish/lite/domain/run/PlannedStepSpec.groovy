package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.MODEL
import static org.lilradish.lite.domain.run.fixture.Runs.asking
import static org.lilradish.lite.domain.run.fixture.Runs.code
import static org.lilradish.lite.domain.run.fixture.Runs.codeStep
import static org.lilradish.lite.domain.run.fixture.Runs.gives
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.pinned
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.routeStep
import static org.lilradish.lite.domain.run.fixture.Runs.standing
import static org.lilradish.lite.domain.run.fixture.Runs.stepId
import static org.lilradish.lite.domain.run.fixture.Runs.takes
import static org.lilradish.lite.domain.run.fixture.Runs.workflowHalf
import static org.lilradish.lite.domain.run.fixture.Runs.workflowStep

import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.workflow.StepId
import spock.lang.Specification

class PlannedStepSpec extends Specification {

    def "a step is refused placed before the first"() {
        when:
        new PlannedStep(stepId(1), order, new StepId("step_1"), runsOf("a question"), person(), 1, null, [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PlannedStep order must be positive: " + order

        where:
        order << [0, -1]
    }

    def "a step names a producer and tries exactly where what it runs produces, and a reviewer only there"() {
        when:
        plannedWith(runs, producer, tries, reviewer)

        then:
        noExceptionThrown()

        where:
        runs         | producer | tries | reviewer
        "a question" | true     | true  | false
        "a question" | true     | true  | true
        "code"       | true     | true  | false
        "code"       | true     | true  | true
        "a workflow" | false    | false | false
        "a route"    | false    | false | false
    }

    def "a step is refused where it names a producer, tries or a reviewer otherwise"() {
        when:
        plannedWith(runs, producer, tries, reviewer)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PlannedStep step_1 names a producer, tries and a reviewer exactly where what it runs produces"

        where:
        [runs, producer, tries, reviewer] << [
                ["a question", "code", "a workflow", "a route"], [true, false], [true, false], [true, false],
        ].combinations().findAll { runs, producer, tries, reviewer ->
            boolean produces = runs == "a question" || runs == "code"
            !(produces ? producer && tries : !producer && !tries && !reviewer)
        }
    }

    def "a step is refused declaring fewer tries than one"() {
        when:
        new PlannedStep(stepId(1), 1, new StepId("step_1"), runsOf("a question"), person(), tries, null, [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PlannedStep tries must be positive: " + tries

        where:
        tries << [0, -1]
    }

    def "the tries a step declares are its own, and none where what it runs makes none"() {
        given:
        def planned = runs == "a question" ? question(1, person(), tries) : (runs == "code" ? codeStep(1, tries)
                : (runs == "a workflow" ? workflowStep(1) : routeStep(1)))

        expect:
        planned.declaredTries() == declared

        where:
        runs         | tries || declared
        "a question" | 1     || 1
        "a question" | 3     || 3
        "code"       | 2     || 2
        "a workflow" | null  || 0
        "a route"    | null  || 0
    }

    private static PlannedStep plannedWith(String runs, boolean producer, boolean tries, boolean reviewer) {
        new PlannedStep(stepId(1), 1, new StepId("step_1"), runsOf(runs), producer ? code() : null, tries ? 2 : null,
                reviewer ? MODEL : null, [])
    }

    private static StepRuns runsOf(String runs) {
        switch (runs) {
            case "a question": return asking(1, takes([]), gives([standing("answer")]))
            case "code": return new StepRuns.Code("tidy", null)
            case "a workflow": return new StepRuns.Workflow(pinned(1))
            case "a route": return new StepRuns.Route()
            default: throw new IllegalArgumentException(runs)
        }
    }
}
