package org.lilradish.lite.domain.registry

import spock.lang.Specification

class ContentProblemSpec extends Specification {

    def "a problem says what is wrong and where, and how far past a bound only where it says one"() {
        expect:
        new ContentProblem(ContentProblemCode.CONSTANT_TOO_LONG, place, excess).excess() == excess
        ContentProblem.recordComponents*.name == ["code", "place", "excess"]

        where:
        place                                      | excess
        new ContentPlace.Whole(ContentPart.GIVES)  | null
        new ContentPlace.Whole(ContentPart.HELPER) | 12L
    }

    def "a problem says what is wrong and where, whether or not it says by how much, or is refused"() {
        when:
        new ContentProblem(code, place, excess)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        code                                  | place                                     | excess || expectedMessage
        null                                  | new ContentPlace.Whole(ContentPart.GIVES) | null   || "ContentProblem code must not be null"
        null                                  | new ContentPlace.Whole(ContentPart.GIVES) | 12L    || "ContentProblem code must not be null"
        ContentProblemCode.NOTHING_GIVEN_BACK | null                                      | null   || "ContentProblem place must not be null"
        ContentProblemCode.NOTHING_GIVEN_BACK | null                                      | 12L    || "ContentProblem place must not be null"
    }
}
