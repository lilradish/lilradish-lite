package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class FieldNameSpec extends Specification {

    def "keeps the text it was constructed with, unnormalised"() {
        when:
        def identifier = new FieldName("body")

        then:
        identifier.value() == "body"
    }

    def "delegates its rejection to Identifiers under its own label"() {
        when:
        new FieldName(value)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        value    || expectedException        | expectedMessage
        null     || NullPointerException     | "FieldName must not be null"
        ""       || IllegalArgumentException | "FieldName must not be empty"
        "Body"   || IllegalArgumentException | "FieldName must start with a lowercase letter"
        "a" * 64 || IllegalArgumentException | "FieldName must not exceed 63 characters"
    }
}
