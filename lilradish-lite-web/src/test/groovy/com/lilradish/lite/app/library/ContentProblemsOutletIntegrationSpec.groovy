package com.lilradish.lite.app.library

import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.testutil.ApplicationStore
import org.libprunus.spring.error.ApiErrorHandler
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.web.method.ControllerAdviceBean
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * The advice answering a refusal for what a version holds, in the order the dispatcher asks advice in. The
 * outlet answers every refusal a code carries, so an advice asked after it is never asked at all, and the
 * problems would leave the server as a bare refusal. Started whole, so every kind's check was found too.
 */
@SpringBootTest
@Import(ApplicationStore)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ContentProblemsOutletIntegrationSpec extends Specification {

    @Autowired
    private ApplicationContext context

    def "the advice naming every place that does not hold is asked before the outlet answering every refusal"() {
        given:
        def found = ControllerAdviceBean.findAnnotatedBeans(context)
        def asked = found*.beanType
        def problems = found.find { it.beanType == ContentProblemsOutlet }
        def outlet = found.find { ApiErrorHandler.isAssignableFrom(it.beanType) }

        expect: "each found, and once, so their order is a reading"
        asked.count(ContentProblemsOutlet) == 1
        asked.count { ApiErrorHandler.isAssignableFrom(it) } == 1

        and:
        problems.order < outlet.order
        asked.indexOf(ContentProblemsOutlet) < asked.indexOf(outlet.beanType)

        and: "every kind's check found, which the checks refuse to start without"
        context.getBeansOfType(ContentCheck).values()*.kind().toSet() == EntryKind.values().toList().toSet()
    }
    def "a drawing reference is answered with its unknown name"() {
        when:
        def answer = ContentProblemsOutlet.problemAnswer(new ContentProblem(
                ContentProblemCode.DRAWING_NAMES_UNKNOWN, new ContentPlace.AtDrawing("missing"), null, null))

        then:
        answer.code() == "drawing_names_unknown"
        answer.part() == "drawing"
        answer.name() == "missing"
        answer.fieldId() == null
    }

    def "unknown instruction references are published as raw names at the instruction, never markup"() {
        given:
        def mapper = JsonMapper.builder().build()

        when:
        def answer = mapper.readValue(mapper.writeValueAsString(ContentProblemsOutlet.problemAnswer(
                new ContentProblem(ContentProblemCode.INSTRUCTION_NAMES_UNKNOWN,
                        new ContentPlace.AtInstruction(name), null, null))), Map)

        then:
        answer == [code: "instruction_names_unknown", part: "instruction", name: name]

        where:
        name << ["unknown", "a.b", "", "<script>", "\u65e5\u672c\u8a9e"]
    }

    def "a new or stored step collision sends the actual display name and only a real step key"() {
        given:
        def mapper = JsonMapper.builder().build()
        def problem = new ContentProblem(ContentProblemCode.STEP_LABEL_REPEATED,
                new ContentPlace.AtStepNamed(stepId, "\u78ba\u8a8d\u3059\u308b\u202e"), null, null)

        when:
        def answer = mapper.readValue(mapper.writeValueAsString(ContentProblemsOutlet.problemAnswer(problem)), Map)

        then:
        answer == expected + [code: "step_label_repeated", part: "steps", name: "\u78ba\u8a8d\u3059\u308b\u202e"]
        answer.containsKey("stepId") == (stepId != null)
        !answer.containsKey("fieldId")
        !answer.containsKey("bindingId")

        where:
        stepId                                                        || expected
        null                                                          || [:]
        UUID.fromString("0000000c-0000-4000-8000-000000000101")          || [stepId: "0000000c-0000-4000-8000-000000000101"]
    }

    def "only a deployment file limit problem sends all three deployment limits"() {
        given:
        def field = UUID.randomUUID()
        def place = new ContentPlace.AtField(com.lilradish.lite.domain.registry.ContentPart.GIVES, field)
        def mapper = JsonMapper.builder().build()

        when:
        def deployment = mapper.readValue(mapper.writeValueAsString(ContentProblemsOutlet.problemAnswer(
                new ContentProblem(ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT, place, null,
                        new ContentProblem.FileLimits(104_857_600L, 100, 1_073_741_824L)))), Map)
        def ordinary = mapper.readValue(mapper.writeValueAsString(ContentProblemsOutlet.problemAnswer(
                new ContentProblem(ContentProblemCode.FILE_SIZE_MISSING, place, null, null))), Map)

        then:
        deployment.code == "file_limit_past_deployment"
        deployment.fileLimits == [maximumBytes: 104_857_600L, maximumPerField: 100,
                                  maximumTotalBytes: 1_073_741_824L]
        !ordinary.containsKey("fileLimits")
        !ordinary.containsKey("excess")
    }

    def "a case output field is answered with its case, step and field keys"() {
        given:
        def step = UUID.randomUUID()
        def routeCase = UUID.randomUUID()
        def field = UUID.randomUUID()
        def problem = new ContentProblem(ContentProblemCode.CASE_GIVES_UNDECLARED,
                new ContentPlace.AtCaseField(step, routeCase, field), null, null)

        when:
        def answer = ContentProblemsOutlet.problemAnswer(problem)

        then:
        answer.code() == "case_gives_undeclared"
        answer.part() == "steps"
        answer.stepId() == step
        answer.caseId() == routeCase
        answer.fieldId() == field
        answer.bindingId() == null
        answer.termId() == null
        answer.fields() == null
    }

    def "a step naming fields of what it runs is answered with its key and those fields in their order, and nothing else"() {
        given:
        def step = UUID.randomUUID()
        def problem = new ContentProblem(ContentProblemCode.SEARCH_STANDING_MISSING,
                new ContentPlace.AtStepFields(step, ["references", "other"]), null, null)

        when:
        def answer = ContentProblemsOutlet.problemAnswer(problem)

        then:
        answer == new ContentProblemsOutlet.ProblemAnswer("search_standing_missing", "steps", null, null, step, null, null,
                null, ["references", "other"], null, null)
    }
}
