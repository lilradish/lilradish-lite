package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.fixture.Runs.MODEL
import static com.lilradish.lite.domain.run.fixture.Runs.asking
import static com.lilradish.lite.domain.run.fixture.Runs.code
import static com.lilradish.lite.domain.run.fixture.Runs.codeStep
import static com.lilradish.lite.domain.run.fixture.Runs.gives
import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.model
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.pinned
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.routeStep
import static com.lilradish.lite.domain.run.fixture.Runs.searchQuestion
import static com.lilradish.lite.domain.run.fixture.Runs.standing
import static com.lilradish.lite.domain.run.fixture.Runs.standingAll
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.takes
import static com.lilradish.lite.domain.run.fixture.Runs.text
import static com.lilradish.lite.domain.run.fixture.Runs.webSearch
import static com.lilradish.lite.domain.run.fixture.Runs.workflowHalf
import static com.lilradish.lite.domain.run.fixture.Runs.workflowStep

import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.StepId
import spock.lang.Specification

class PlannedStepSpec extends Specification {

    static final Binding TOPIC_BOUND = new Binding(key(801), Pointer.parse("topic"),
            new BindingSource.WorkflowInput(Pointer.parse("topic")))

    def "a step is refused placed before the first"() {
        when:
        new PlannedStep(stepId(1), order, new StepId("step_1"), null, runsOf("a question"), person(), 1, null, [])

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
        "a search"   | true     | true  | false
        "a search"   | true     | true  | true
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
                ["a question", "code", "a search", "a workflow", "a route"], [true, false], [true, false], [true, false],
        ].combinations().findAll { runs, producer, tries, reviewer ->
            boolean produces = runs == "a question" || runs == "code" || runs == "a search"
            !(produces ? producer && tries : !producer && !tries && !reviewer)
        }
    }

    def "a search is refused made by code even with input bindings"() {
        when:
        new PlannedStep(stepId(1), 1, new StepId("step_1"), null, runsOf("a search"), producer, 1, null, bindings)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "PlannedStep step_1 searches with a pinned question, asked of a model or a person"

        where:
        producer | bindings
        code()   | []
        code()   | [TOPIC_BOUND]
    }

    def "a pinned search question keeps its ordinary input bindings with a model or a person"() {
        given:
        def runs = new StepRuns.Search("web_search", webSearch(),
                searchQuestion(1, webSearch(), takes([text("topic")])), "web_search", standingAll(FieldStanding.NEVER))

        when:
        def planned = new PlannedStep(stepId(1), 1, new StepId("step_1"), null, runs, producer, 1, null, [TOPIC_BOUND])

        then:
        planned.bindings() == [TOPIC_BOUND]
        planned.runs() == runs

        where:
        producer << [person(), model()]
    }

    def "a search made by a model is planned, declaring the tries it was given"() {
        when:
        def planned = new PlannedStep(stepId(1), 1, new StepId("step_1"), null, runsOf("a search"), model(), 2, reviewer, [])

        then:
        noExceptionThrown()
        planned.declaredTries() == 2

        where:
        reviewer << [null, MODEL]
    }

    def "a step is refused declaring fewer tries than one"() {
        when:
        new PlannedStep(stepId(1), 1, new StepId("step_1"), null, runsOf("a question"), person(), tries, null, [])

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
        Producer made = runs == "a search" ? person() : code()
        new PlannedStep(stepId(1), 1, new StepId("step_1"), null, runsOf(runs), producer ? made : null, tries ? 2 : null,
                reviewer ? MODEL : null, [])
    }

    private static StepRuns runsOf(String runs) {
        switch (runs) {
            case "a question": return asking(1, takes([]), gives([standing("answer")]))
            case "code": return new StepRuns.Code("tidy", null)
            case "a search": return new StepRuns.Search("web_search", null,
                    searchQuestion(1), "web_search", standingAll(FieldStanding.ALWAYS))
            case "a workflow": return new StepRuns.Workflow(pinned(1), workflowHalf(DeclarationSide.GIVES))
            case "a route": return routeStep(1).runs()
            default: throw new IllegalArgumentException(runs)
        }
    }
}
