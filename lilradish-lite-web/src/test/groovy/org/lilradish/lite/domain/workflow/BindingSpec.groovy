package org.lilradish.lite.domain.workflow

import spock.lang.Specification

class BindingSpec extends Specification {

    static final UUID KEY = UUID.fromString("0000000e-0000-4000-8000-000000000101")

    static final Pointer ORDER = Pointer.parse("order")

    static final BindingSource INPUT = new BindingSource.WorkflowInput(ORDER)

    def "a binding names its key and its source"() {
        when:
        new Binding(id, ORDER, source)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        id   | source || expectedMessage
        null | INPUT  || "Binding id must not be null"
        KEY  | null   || "Binding source must not be null"
    }
}
