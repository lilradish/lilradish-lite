package com.lilradish.lite.app.run

import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.failure.RefusalCode
import org.libprunus.spring.error.ApiErrorHandler
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.ServletWebRequest
import spock.lang.Specification

class GivesOtherwiseOutletSpec extends Specification {

    static final UUID CHECK = UUID.fromString("00000009-0000-4000-8000-000000000c02")

    def "a gives-otherwise refusal accepts its own code and refuses every other code"() {
        given:
        def fault = new CodeError.Fault(CodeErrorReason.GIVES_A_LIST_NOT_HERE, [new FieldName("grade")], null, null)

        when:
        def refusal = null
        try {
            refusal = new GivesOtherwiseRefusal(code, "The code gives otherwise.", fault)
        } catch (IllegalArgumentException rejected) {
            assert code != RefusalCode.CODE_STEP_GIVES_OTHERWISE
        }

        then:
        (refusal != null) == (code == RefusalCode.CODE_STEP_GIVES_OTHERWISE)
        refusal == null || refusal.fault() == fault

        where:
        code << RefusalCode.values()
    }

    private static ServletWebRequest request() {
        new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse())
    }

    def "a refusal for giving otherwise is answered with the field and what reads it, as a try names them"() {
        given:
        def refused = RunRefusal.givesOtherwise(
                new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, [new FieldName("receipt")], null, readBy))

        when:
        def answered = new GivesOtherwiseOutlet(new ApiErrorHandler()).givesOtherwise(refused as GivesOtherwiseRefusal,
                request())

        then:
        def problem = answered.body as ProblemDetail
        problem.properties.readBy == answer
        problem.properties.field == "receipt"

        and: "answered as the outlet answers the refusal, under its code and sentence"
        answered.statusCode == HttpStatus.CONFLICT
        problem.properties.code == "CODE_STEP_GIVES_OTHERWISE"
        problem.detail == RunRefusal.CODE_STEP_GIVES_OTHERWISE.sentence

        where:
        readBy                                                                 || answer
        new CodeError.StepReads(CHECK)                                         || new StepAnswers.ReadByAnswer(CHECK, null)
        new CodeError.OutputReads([new FieldName("result"), new FieldName("receipt")]) || new StepAnswers.ReadByAnswer(null, "result.receipt")
    }

    /** A list the code step pins that the group does not hold refuses it, which no step or output is to blame for. */
    def "a refusal for a list not here is answered with the field pinning it, naming nothing as reading it"() {
        given:
        def refused = RunRefusal.givesOtherwise(new CodeError.Fault(CodeErrorReason.GIVES_A_LIST_NOT_HERE,
                [new FieldName("lines"), new FieldName("grade")], null, null))

        when:
        def answered = new GivesOtherwiseOutlet(new ApiErrorHandler()).givesOtherwise(refused as GivesOtherwiseRefusal,
                request())

        then:
        def problem = answered.body as ProblemDetail
        problem.properties.field == "lines.grade"
        !problem.properties.containsKey("readBy")

        and:
        answered.statusCode == HttpStatus.CONFLICT
        problem.properties.code == "CODE_STEP_GIVES_OTHERWISE"
    }

    /** Nothing else is why a code step gives otherwise, so a fault of another reason is a mistake, never answered. */
    def "a refusal for giving otherwise over a fault of any other reason is never answered"() {
        given:
        def reasonsWithoutFieldPath = [CodeErrorReason.GAVE_NOTHING, CodeErrorReason.FILE_TOTAL_TOO_LARGE,
                                       CodeErrorReason.FAILED_ON_THIS_SIDE, CodeErrorReason.SAID_NOTHING]
        def about = reason in reasonsWithoutFieldPath ? [] : [new FieldName("receipt")]
        def refused = RunRefusal.givesOtherwise(new CodeError.Fault(reason, about,
                reason == CodeErrorReason.NOT_DECLARED ? new CodeError.UndeclaredMember("extra", false) : null, null))
        def sent = request()

        when:
        new GivesOtherwiseOutlet(new ApiErrorHandler()).givesOtherwise(refused as GivesOtherwiseRefusal, sent)

        then:
        def mistaken = thrown(IllegalStateException)
        mistaken.message == "No code step is refused for giving otherwise over " + reason.published()
        !sent.response.committed
        sent.response.contentAsString == ""

        where:
        reason << CodeErrorReason.values() - [CodeErrorReason.GIVES_OTHERWISE, CodeErrorReason.GIVES_A_LIST_NOT_HERE]
    }
}
