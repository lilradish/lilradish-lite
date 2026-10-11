package com.lilradish.lite.domain.registry

import spock.lang.Specification

class ContentProblemSpec extends Specification {

    def "a problem says what is wrong and where, and how far past a bound only where it says one"() {
        expect:
        new ContentProblem(ContentProblemCode.CONSTANT_TOO_LONG, place, excess, null).excess() == excess
        ContentProblem.recordComponents*.name == ["code", "place", "excess", "fileLimits"]

        where:
        place                                      | excess
        new ContentPlace.Whole(ContentPart.GIVES)  | null
        new ContentPlace.Whole(ContentPart.HELPER) | 12L
    }

    def "only a deployment file problem carries all three deployment limits"() {
        given:
        def place = new ContentPlace.Whole(ContentPart.GIVES)
        def limits = new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L)

        expect:
        new ContentProblem(ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT, place, null, limits).fileLimits() == limits
        new ContentProblem(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT, place, null, limits).fileLimits() == limits
        new ContentProblem(ContentProblemCode.FILE_SIZE_MISSING, place, null, null).fileLimits() == null

        when:
        new ContentProblem(code, place, null, supplied)

        then:
        thrown(IllegalArgumentException)

        where:
        code                                          | supplied
        ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT | null
        ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT | null
        ContentProblemCode.FILE_SIZE_MISSING         | new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L)
    }

    def "a problem says what is wrong and where, whether or not it says by how much, or is refused"() {
        when:
        new ContentProblem(code, place, excess, null)

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
