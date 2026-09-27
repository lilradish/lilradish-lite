package org.lilradish.lite.domain.workflow

import spock.lang.Specification

class BindingSourceSpec extends Specification {

    static final UUID STEP = UUID.fromString("0000000c-0000-4000-8000-000000000101")

    def "what the workflow takes is read at a pointer"() {
        when:
        new BindingSource.WorkflowInput(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "BindingSource.WorkflowInput pointer must not be null"
    }

    def "a step's output is read by the step's key, at a pointer"() {
        when:
        new BindingSource.StepOutput(step, pointer)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        step | pointer                 || expectedMessage
        null | Pointer.parse("order")  || "BindingSource.StepOutput step must not be null"
        STEP | null                    || "BindingSource.StepOutput pointer must not be null"
    }

    def "a constant written into the version is one"() {
        when:
        new BindingSource.Written(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "BindingSource.Written constant must not be null"
    }
}
