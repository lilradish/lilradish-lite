package org.lilradish.lite.app.filling

import org.libprunus.spring.error.ApiErrorHandler
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.filling.FillProblem
import org.lilradish.lite.domain.filling.FillProblems
import org.lilradish.lite.domain.filling.FillReason
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.ServletWebRequest
import spock.lang.Specification

class ValueProblemsOutletSpec extends Specification {

    static final FillProblem EMAIL_MISSING =
            new FillProblem(new FillPath([new FillPath.Named(new FieldName("email"))]), FillReason.MISSING)

    static final FillProblem SECOND_PHONE_MALFORMED = new FillProblem(
            new FillPath([new FillPath.Named(new FieldName("phones")), new FillPath.Place(1)]), FillReason.MALFORMED)

    /** Past the most named a problem is only counted, so how many were found is never how many are named. */
    def "a refusal is answered with the problems it names and how many were found in all, which is more"() {
        given:
        def refused = new ValueProblemsRefusal(new FillProblems([EMAIL_MISSING, SECOND_PHONE_MALFORMED], 21))

        when:
        def answered = new ValueProblemsOutlet(new ApiErrorHandler()).valuesRefused(
                refused, new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()))

        then:
        def problem = answered.body as ProblemDetail
        problem.properties.problemsFound == 21
        problem.properties.problems == [
                new ValueProblemsOutlet.ProblemAnswer(["email"], "missing"),
                new ValueProblemsOutlet.ProblemAnswer(["phones", 1], "malformed"),
        ]

        and: "answered as the outlet answers the refusal, under its code and sentence"
        answered.statusCode == HttpStatus.BAD_REQUEST
        problem.properties.code == "VALUE_DOES_NOT_FIT"
        problem.detail == "Each value is written as its field takes it."
    }
}
