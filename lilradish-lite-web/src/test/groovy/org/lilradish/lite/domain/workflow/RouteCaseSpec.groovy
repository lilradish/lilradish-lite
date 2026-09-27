package org.lilradish.lite.domain.workflow

import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class RouteCaseSpec extends Specification {

    static final UUID KEY = UUID.fromString("0000000d-0000-4000-8000-000000000101")

    static final Binding FILLED = new Binding(
            UUID.fromString("0000000e-0000-4000-8000-000000000101"),
            Pointer.parse("order"),
            new BindingSource.WorkflowInput(Pointer.parse("order")))

    static final EntryVersionId TARGET = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101"))

    /** The fallback is the case on no term; what a case holds is its own, whatever the list it came from does next. */
    def "a case is on a term, or on none as the fallback, and keeps the bindings it was given"() {
        given:
        def bindings = [FILLED]

        when:
        def routeCase = new RouteCase(KEY, term, target, bindings)
        bindings.clear()

        then:
        routeCase.term() == term
        routeCase.target() == target
        routeCase.bindings() == [FILLED]

        where:
        term      | target
        "Billing" | TARGET
        null      | TARGET
        "Billing" | null
    }

    def "a case names its key and its bindings"() {
        when:
        new RouteCase(id, "Billing", null, bindings)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        id   | bindings || expectedMessage
        null | []       || "RouteCase id must not be null"
        KEY  | null     || "RouteCase bindings must not be null"
    }
}
