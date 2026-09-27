package org.lilradish.lite.domain.declaration

import org.lilradish.lite.domain.registry.ContentProblemCode
import spock.lang.Specification

class FieldProblemSpec extends Specification {

    def "a problem is held where it sits, apart from the list it was handed in"() {
        given:
        def handed = [2, 1]

        when:
        def problem = new FieldProblem(ContentProblemCode.LONGEST_MISSING, handed)
        handed.add(0)

        then:
        problem.at() == [2, 1]
        problem.code() == ContentProblemCode.LONGEST_MISSING
    }

    def "a problem names a field, and what is wrong with it"() {
        when:
        new FieldProblem(code, at)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        code                                 | at  || expectedException        | expectedMessage
        ContentProblemCode.LONGEST_MISSING   | []  || IllegalArgumentException | "FieldProblem at must name a field"
        ContentProblemCode.LONGEST_MISSING   | null || NullPointerException    | "FieldProblem at must not be null"
        null                                 | [0] || NullPointerException     | "FieldProblem code must not be null"
    }
}
