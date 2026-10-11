package com.lilradish.lite.app.library

import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblemCode
import spock.lang.Specification

class ContentProblemReportSpec extends Specification {

    static final UUID STEP = UUID.fromString("0000000c-0000-4000-8000-000000000801")
    static final UUID CASE = UUID.fromString("0000000d-0000-4000-8000-000000000801")
    static final UUID FIELD = UUID.fromString("0000000f-0000-4000-8000-000000000801")

    def "case, case output, and case input share twenty visible places while all are counted"() {
        given:
        def report = new ContentProblemReport()

        when:
        report.add(ContentProblemCode.CASE_REPEATED, new ContentPlace.AtCase(STEP, CASE))
        report.add(ContentProblemCode.CASE_GIVES_UNDECLARED, new ContentPlace.AtCaseField(STEP, CASE, FIELD))
        19.times {
            report.add(ContentProblemCode.INPUT_UNBOUND, new ContentPlace.AtInput(STEP, CASE, FIELD))
        }
        report.add(ContentProblemCode.NO_STEPS, new ContentPlace.Whole(ContentPart.STEPS))
        def found = report.result()

        then:
        found.total() == 22
        found.problems().size() == 21
        found.problems().first().place() == new ContentPlace.AtCase(STEP, CASE)
        found.problems().last().place() == new ContentPlace.Whole(ContentPart.STEPS)
        found.problems().count { it.place() instanceof ContentPlace.AtInput } == 18
    }

    def "only the first two hundred across cases and other content are kept in traversal order"() {
        given:
        def report = new ContentProblemReport()

        when:
        5.times { report.add(ContentProblemCode.NO_STEPS, new ContentPlace.Whole(ContentPart.STEPS)) }
        10.times { caseIndex ->
            def routeCase = new UUID(0L, caseIndex + 1L)
            20.times { fieldIndex ->
                report.add(ContentProblemCode.CASE_GIVES_UNDECLARED,
                        new ContentPlace.AtCaseField(STEP, routeCase, new UUID(1L, fieldIndex + 1L)))
            }
        }
        5.times { report.add(ContentProblemCode.NO_STEPS, new ContentPlace.Whole(ContentPart.STEPS)) }
        def found = report.result()

        then:
        found.total() == 210
        found.problems().size() == 200
        found.problems()[4].place() == new ContentPlace.Whole(ContentPart.STEPS)
        found.problems()[5].place() == new ContentPlace.AtCaseField(STEP, new UUID(0L, 1L), new UUID(1L, 1L))
        found.problems().last().place() == new ContentPlace.AtCaseField(STEP, new UUID(0L, 10L), new UUID(1L, 15L))
    }
}
