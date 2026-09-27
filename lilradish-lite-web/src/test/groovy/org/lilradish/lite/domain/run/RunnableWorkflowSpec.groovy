package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.stepId
import static org.lilradish.lite.domain.run.fixture.Runs.workflowOf

import spock.lang.Specification

class RunnableWorkflowSpec extends Specification {

    def "a workflow's steps are refused out of their order counted from one"() {
        when:
        workflowOf(orders.collect { question(it, person(), 1) })

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "RunnableWorkflow holds a step out of its order"

        where:
        orders << [[2], [1, 1], [2, 1], [1, 3]]
    }

    def "a workflow's step is found by its key, and none is for a key not among them"() {
        given:
        def steps = [question(1, person(), 1), question(2, person(), 1), question(3, person(), 1)]
        def workflow = workflowOf(steps)

        expect:
        workflow.step(stepId(2)) == Optional.of(steps[1])
        workflow.step(new WorkflowStepId(key(999))) == Optional.empty()
    }
}
