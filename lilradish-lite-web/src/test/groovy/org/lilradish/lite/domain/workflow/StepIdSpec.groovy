package org.lilradish.lite.domain.workflow

import spock.lang.Specification

class StepIdSpec extends Specification {

    def "keeps the text it was constructed with, unnormalised"() {
        when:
        def identifier = new StepId("extract")

        then:
        identifier.value() == "extract"
    }

    def "delegates its rejection to Identifiers under its own label"() {
        when:
        new StepId(value)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        value     || expectedException        | expectedMessage
        null      || NullPointerException     | "StepId must not be null"
        ""        || IllegalArgumentException | "StepId must not be empty"
        "Extract" || IllegalArgumentException | "StepId must start with a lowercase letter"
        "a" * 64  || IllegalArgumentException | "StepId must not exceed 63 characters"
    }
}
