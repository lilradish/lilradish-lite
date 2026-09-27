package org.lilradish.lite.domain.codestep

import spock.lang.Specification

class CodeStepNameSpec extends Specification {

    def "keeps the name it was constructed with, as long as the longest a name may be"() {
        when:
        def name = new CodeStepName(value)

        then:
        name.value() == value

        where:
        value << ["send_reply", "a", "a" * 63, "stamp2_reference"]
    }

    def "delegates its rejection to Identifiers under its own label"() {
        when:
        new CodeStepName(value)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        value        || expectedException        | expectedMessage
        null         || NullPointerException     | "CodeStepName must not be null"
        ""           || IllegalArgumentException | "CodeStepName must not be empty"
        "Send_reply" || IllegalArgumentException | "CodeStepName must start with a lowercase letter"
        "2send"      || IllegalArgumentException | "CodeStepName must start with a lowercase letter"
        "send-reply" || IllegalArgumentException | "CodeStepName must contain only lowercase letters, digits and _"
        "a" * 64     || IllegalArgumentException | "CodeStepName must not exceed 63 characters"
    }
}
