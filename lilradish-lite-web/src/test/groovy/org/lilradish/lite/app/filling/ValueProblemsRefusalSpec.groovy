package org.lilradish.lite.app.filling

import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.filling.FillProblem
import org.lilradish.lite.domain.filling.FillProblems
import org.lilradish.lite.domain.filling.FillReason
import spock.lang.Specification

class ValueProblemsRefusalSpec extends Specification {

    static final FillProblem EMAIL_MISSING =
            new FillProblem(new FillPath([new FillPath.Named(new FieldName("email"))]), FillReason.MISSING)

    def "a refusal names the problems it was given and how many were found in all, under the one sentence"() {
        when:
        def refused = new ValueProblemsRefusal(new FillProblems([EMAIL_MISSING], 21))

        then:
        refused.problems() == [EMAIL_MISSING]
        refused.found() == 21
        refused.errorCode() == RefusalCode.VALUE_DOES_NOT_FIT
        refused.message == "Each value is written as its field takes it."
    }

    def "a refusal is never raised without the problems it names"() {
        when:
        new ValueProblemsRefusal(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ValueProblemsRefusal problems must not be null"
    }
}
