package org.lilradish.lite.app.run

import static java.nio.charset.StandardCharsets.UTF_8
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.filling.ValueProblemsRefusal
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.filling.FillProblem
import org.lilradish.lite.domain.filling.FillProblems
import org.lilradish.lite.domain.filling.FillReason
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.run.Reasons
import org.lilradish.lite.domain.run.ReviewOutcome
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.StepAct
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.web.GroupPermissionRequired
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What every act on a step is answered with, over a real dispatcher and a replaced store: which act on which try
 * of which step the request became, the step read once it landed, and every refusal met before the store is asked.
 */
@WebMvcTest([RunStepsController, StepActsController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class StepActsControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000001401")

    static final UUID RUN = UUID.fromString("00000008-0000-4000-8000-000000001401")

    static final UUID STEP = UUID.fromString("00000009-0000-4000-8000-000000001401")

    static final String ADDRESS = "/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}/tries"

    static final String SENDING = "/api/groups/${GROUP}/runs/${RUN}/steps/${STEP}/sending"

    /** Worked out apart from the controller: every code point of the values and the reason as a twelve-byte escape. */
    static final long LARGEST_ANSWER = 12 * (Declaration.MOST_SENT + Reasons.LONGEST) + '{"values":,"why":""}'.length()

    static final StepAnswers.StepAnswer READ = new StepAnswers.StepAnswer(
            new StepAnswers.HeaderAnswer(RUN, 3, UUID.fromString("00000007-0000-4000-8000-000000001401"), "running",
                    new RunsController.AtAnswer(STEP, "summarise"), ["stop"], new StepAnswers.ProgressAnswer(0, 1)),
            [:],
            new StepAnswers.StepRowAnswer(STEP, 1, "summarise",
                    new StepAnswers.RunsAnswer("code_step", null, null, null, null, "stamp"),
                    new StepAnswers.WhoAnswer("code", null, null, null), null, "not_started", null, [], null,
                    new StepAnswers.CostAnswer(false, null, null, null, null, null), null, null, null, null, [], []),
            null, null, null, [], null, null)

    /** A character built as it runs, so no source text or tool reading it can turn it into another. */
    private static String character(int codePoint) {
        new String(Character.toChars(codePoint))
    }

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private RequestMappingHandlerMapping mappings

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private RunSteps steps

    @MockitoBean
    private StepActs acts

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(READ)
    }

    /** As this application's own pages send it, which a browser says of every request it makes. */
    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private MvcResult sendingBody(MockHttpServletRequestBuilder request, String body) {
        sending(request.contentType(MediaType.APPLICATION_JSON).content(body))
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static String reviewing(Object decisions) {
        JSON.writeValueAsString([decisions: decisions])
    }

    /** The one act asked, and only once it has landed the step read, which is what is answered. */
    private void actedOnlyBy(MvcResult answered, Closure<?> asked) {
        assert answered.response.status == 200
        assert documentOf(answered).get("step").get("stepId").asString() == STEP.toString()
        def order = Mockito.inOrder(acts, steps)
        asked(order.verify(acts))
        order.verify(steps).step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)
        Mockito.verifyNoMoreInteractions(acts, steps)
    }

    private void refusedUnasked(MvcResult answered, String code) {
        assert documentOf(answered).get("code").asString() == code
        Mockito.verifyNoInteractions(acts, steps)
    }

    /**
     * Read off the mappings the dispatcher resolved, and held whole against the permission each act takes: a
     * handler guarded by another would let in whoever holds that one.
     */
    def "every act on a step is asked of the permission the act itself takes, and there are no others"() {
        given:
        def asked = mappings.handlerMethods.values()
                .findAll { it.beanType == StepActsController }
                .collectEntries { [(it.method.name): it.getMethodAnnotation(GroupPermissionRequired).value()] }

        expect:
        asked == [askAgain  : StepAct.ASK_AGAIN.permission(),
                  review    : StepAct.REVIEW.permission(),
                  answer    : StepAct.ANSWER.permission(),
                  trySending: StepAct.TRY_SENDING.permission()]
    }

    def "asks again for the try named, answering with the step as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(put(ADDRESS + "/2"))

        then:
        actedOnlyBy(answered) { it.askAgain(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 2, READER) }
        documentOf(answered).get("run").get("state").asString() == "running"
    }

    def "reviews the try named with every decision as it was sent, answering with the step as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        def body = reviewing([summary: [outcome: "assured"], note: [outcome: "refused", why: "Line one.\n\tLine two."]])

        when:
        def answered = sendingBody(put(ADDRESS + "/1/review"), body)

        then:
        actedOnlyBy(answered) {
            it.review(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 1, READER,
                    [summary: new StepActs.ReviewedRecord(ReviewOutcome.ASSURED, null),
                     note   : new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "Line one.\n\tLine two.")])
        }
    }

    def "refuses a review it cannot read as one, under the code of whatever is refused, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))

        when:
        def answered = sendingBody(put(ADDRESS + "/1/review"), body)

        then:
        answered.response.status == status
        refusedUnasked(answered, code)

        where:
        body                                                                  || status | code
        '[]'                                                                  || 400    | "BODY_UNUSABLE"
        '{"decisions":{},"more":1}'                                           || 400    | "BODY_UNUSABLE"
        '{"decisions":[]}'                                                    || 400    | "BODY_UNUSABLE"
        reviewing([summary: "assured"])                                       || 400    | "BODY_UNUSABLE"
        reviewing([summary: [:]])                                             || 400    | "BODY_UNUSABLE"
        reviewing([summary: [outcome: "maybe"]])                              || 400    | "BODY_UNUSABLE"
        reviewing([summary: [outcome: "assured", why: "Fine."]])              || 400    | "BODY_UNUSABLE"
        reviewing([summary: [outcome: "refused", why: 7]])                    || 400    | "BODY_UNUSABLE"
        reviewing([summary: [outcome: "refused", why: "No.", more: 1]])       || 400    | "BODY_UNUSABLE"
        reviewing([summary: [outcome: "refused"]])                            || 400    | "REASON_MISSING"
        reviewing([summary: [outcome: "refused", why: null]])                 || 400    | "REASON_MISSING"
        reviewing([summary: [outcome: "refused", why: " \t\n "]])            || 400    | "REASON_MISSING"
        reviewing([summary: [outcome: "refused", why: character(0x00A0)]])    || 400    | "REASON_MISSING"
        reviewing([summary: [outcome: "refused", why: character(0xE0041)]])   || 400    | "PROSE_TAG_CHARACTER"
        reviewing([summary: [outcome: "refused", why: character(0x202E)]])    || 400    | "PROSE_DIRECTION_CONTROL"
        reviewing([summary: [outcome: "refused", why: "\r\n"]])               || 400    | "PROSE_LINE_BREAK_CRLF"
        reviewing([summary: [outcome: "refused", why: "One\r\nTwo"]])         || 400    | "PROSE_LINE_BREAK_CRLF"
        reviewing([summary: [outcome: "refused", why: "No" + character(0x202E)]]) || 400 | "PROSE_DIRECTION_CONTROL"
        reviewing([summary: [outcome: "refused", why: "No" + character(0xE0041)]]) || 400 | "PROSE_TAG_CHARACTER"
        reviewing([summary: [outcome: "refused", why: "x" * 2049]])           || 400    | "REASON_UNUSABLE"
        reviewing([summary: [outcome: "refused", why: "No" + character(0x0007)]])  || 400 | "REASON_UNUSABLE"
    }

    def "a reason as long as a reason may be is taken"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))

        when:
        def answered = sendingBody(put(ADDRESS + "/1/review"), reviewing([summary: [outcome: "refused", why: "x" * 2048]]))

        then:
        actedOnlyBy(answered) {
            it.review(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 1, READER,
                    [summary: new StepActs.ReviewedRecord(ReviewOutcome.REFUSED, "x" * 2048)])
        }
    }

    def "answers the try named with what was given and why, answering with the step as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def body = JSON.writeValueAsString([values: [summary: "A printer fire.", note: null, tags: ["a", "b"]],
                                            why   : "Read it twice."])

        when:
        def answered = sendingBody(put(ADDRESS + "/2/answer"), body)

        then:
        actedOnlyBy(answered) {
            it.answer(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 2, READER,
                    new JsonValue.JsonObject([
                            new JsonValue.JsonMember("summary", new JsonValue.JsonString("A printer fire.")),
                            new JsonValue.JsonMember("note", new JsonValue.JsonNull()),
                            new JsonValue.JsonMember("tags", new JsonValue.JsonArray(
                                    [new JsonValue.JsonString("a"), new JsonValue.JsonString("b")]))]),
                    "Read it twice.")
        }
    }

    def "refuses an answer it cannot read as one, under the code of whatever is refused, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sendingBody(put(ADDRESS + "/1/answer"), body)

        then:
        answered.response.status == 400
        refusedUnasked(answered, code)

        where:
        body                                                                          || code
        '[]'                                                                          || "BODY_UNUSABLE"
        '{"why":"Fine."}'                                                             || "BODY_UNUSABLE"
        '{"values":[],"why":"Fine."}'                                                 || "BODY_UNUSABLE"
        '{"values":{},"why":"Fine.","more":1}'                                        || "BODY_UNUSABLE"
        '{"values":{},"why":7}'                                                       || "BODY_UNUSABLE"
        '{"values":{}}'                                                               || "REASON_MISSING"
        '{"values":{},"why":null}'                                                    || "REASON_MISSING"
        JSON.writeValueAsString([values: [:], why: " \n\t"])                          || "REASON_MISSING"
        JSON.writeValueAsString([values: [:], why: "A\r\nB"])                         || "PROSE_LINE_BREAK_CRLF"
        JSON.writeValueAsString([values: [:], why: "A" + character(0x2066)])          || "PROSE_DIRECTION_CONTROL"
        JSON.writeValueAsString([values: [:], why: "A" + character(0xE007F)])         || "PROSE_TAG_CHARACTER"
        JSON.writeValueAsString([values: [:], why: character(0xE007F)])               || "PROSE_TAG_CHARACTER"
        JSON.writeValueAsString([values: [:], why: character(0x2066)])                || "PROSE_DIRECTION_CONTROL"
        JSON.writeValueAsString([values: [:], why: "x" * 2049])                       || "REASON_UNUSABLE"
    }

    /** Values and a reason at their longest, each escaped at its worst, in an answer written with nothing between. */
    def "an answer declared at the largest body taken is answered, and one declared a byte past it is refused unread"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def body = JSON.writeValueAsString([values: [summary: "A printer fire."], why: "Read it twice."])

        when:
        def answered = mockMvc.perform({ servletContext ->
            def request = declaring(servletContext, ADDRESS + "/2/answer", declared)
            request.addHeader("Sec-Fetch-Site", "same-origin")
            request.contentType = MediaType.APPLICATION_JSON_VALUE
            request.content = body.getBytes(UTF_8)
            request
        } as RequestBuilder).andReturn()

        then:
        answered.response.status == status
        documentOf(answered).get("code")?.asString() == code
        Mockito.mockingDetails(acts).invocations.size() == answers
        Mockito.mockingDetails(steps).invocations.size() == answers

        where:
        declared            || status | code            | answers
        LARGEST_ANSWER      || 200    | null            | 1
        LARGEST_ANSWER + 1  || 400    | "BODY_UNUSABLE" | 0
    }

    private static MockHttpServletRequest declaring(ServletContext servletContext, String address, long length) {
        new MockHttpServletRequest(servletContext, "PUT", address) {
            @Override
            int getContentLength() {
                Math.toIntExact(length)
            }

            @Override
            long getContentLengthLong() {
                length
            }
        }
    }

    def "values that do not fit are refused naming each place and why, and the step is not read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def problems = [
                new FillProblem(new FillPath([new FillPath.Named(new FieldName("summary"))]), FillReason.TOO_LONG),
                new FillProblem(new FillPath([new FillPath.Named(new FieldName("tags")), new FillPath.Place(1)]),
                        FillReason.MISSING)]
        Mockito.doThrow(new ValueProblemsRefusal(new FillProblems(problems, problems.size()))).when(acts)
                .answer(any(), any(), any(), Mockito.anyInt(), any(), any(), any())

        when:
        def answered = sendingBody(put(ADDRESS + "/2/answer"),
                JSON.writeValueAsString([values: [summary: "x"], why: "Why."]))

        then:
        answered.response.status == 400
        def document = documentOf(answered)
        document.get("code").asString() == "VALUE_DOES_NOT_FIT"
        document.get("problems") == JSON.readTree('[{"path":["summary"],"reason":"too_long"},{"path":["tags",1],"reason":"missing"}]')
        document.get("problemsFound").asInt() == 2

        and: "no value named in what it says"
        !answered.response.contentAsString.contains('"x"')
        Mockito.verifyNoInteractions(steps)
    }

    /** Past the outlet every other refusal takes, so what found the code step giving otherwise goes out beside it. */
    def "an answer refused for its code step giving otherwise names the field and what reads it, and the step is not read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        Mockito.doThrow(RunRefusal.givesOtherwise(new CodeError.Fault(reason,
                [new FieldName("lines"), new FieldName("grade")], null, readBy))).when(acts)
                .answer(any(), any(), any(), Mockito.anyInt(), any(), any(), any())

        when:
        def answered = sendingBody(put(ADDRESS + "/2/answer"),
                JSON.writeValueAsString([values: [lines: "x"], why: "Why."]))

        then:
        answered.response.status == 409
        def document = documentOf(answered)
        document.get("code").asString() == "CODE_STEP_GIVES_OTHERWISE"
        document.get("field").asString() == "lines.grade"
        document.has("readBy") == (named != null)
        document.get("readBy") == (named == null ? null : JSON.readTree(named))
        Mockito.verifyNoInteractions(steps)

        where:
        reason                                | readBy                                                     || named
        CodeErrorReason.GIVES_OTHERWISE       | new CodeError.StepReads(STEP)                              || """{"step":"${STEP}"}"""
        CodeErrorReason.GIVES_OTHERWISE       | new CodeError.OutputReads([new FieldName("result")])       || '{"output":"result"}'
        CodeErrorReason.GIVES_A_LIST_NOT_HERE | null                                                       || null
    }

    def "sends again what the step is held back or failed on, naming no try, answering with the step as it is then read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(put(SENDING))

        then:
        actedOnlyBy(answered) { it.trySending(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER) }
        documentOf(answered).get("run").get("state").asString() == "running"
    }

    /** No body is taken, so a single byte of one is refused before the store is asked. */
    def "refuses a body or a parameter sending again does not take, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sendingBody(put(SENDING + query), body)

        then:
        answered.response.status == 400
        refusedUnasked(answered, code)

        where:
        query    | body || code
        ""       | "{}" || "BODY_UNUSABLE"
        ""       | " "  || "BODY_UNUSABLE"
        "?now=1" | ""   || "PARAMETER_UNKNOWN"
    }

    def "refuses sending again at an address naming no run or no step, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(put(address))

        then:
        answered.response.status == 404
        refusedUnasked(answered, code)

        where:
        address                                                        || code
        "/api/groups/${GROUP}/runs/not-a-run/steps/${STEP}/sending"    || "RUN_NOT_IN_VIEW"
        "/api/groups/${GROUP}/runs/${RUN}/steps/not-a-step/sending"    || "STEP_NOT_IN_VIEW"
    }

    /** The act has not landed, so the step is not read to answer it. */
    def "a press the step refuses is answered with the refusal alone, and the step is not read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        Mockito.doThrow(refusal.raised()).when(acts).trySending(any(), any(), any(), any())

        when:
        def answered = sending(put(SENDING))

        then:
        answered.response.status == 409
        documentOf(answered).get("code").asString() == refusal.name()
        Mockito.verifyNoInteractions(steps)

        where:
        refusal << [RunRefusal.TRY_SENDING_NOT_OFFERED, RunRefusal.RUN_STOPPED, RunRefusal.STEP_MOVED_ON]
    }

    def "refuses an act at an address naming no run, no step or no try, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(put(address))

        then:
        answered.response.status == status
        refusedUnasked(answered, code)

        where:
        address                                                              || status | code
        "/api/groups/${GROUP}/runs/not-a-run/steps/${STEP}/tries/1"          || 404    | "RUN_NOT_IN_VIEW"
        "/api/groups/${GROUP}/runs/${RUN}/steps/not-a-step/tries/1"          || 404    | "STEP_NOT_IN_VIEW"
        ADDRESS + "/0"                                                       || 409    | "STEP_MOVED_ON"
        ADDRESS + "/01"                                                      || 409    | "STEP_MOVED_ON"
        ADDRESS + "/one"                                                     || 409    | "STEP_MOVED_ON"
        ADDRESS + "/-1"                                                      || 409    | "STEP_MOVED_ON"
        ADDRESS + "/1234567890"                                              || 409    | "STEP_MOVED_ON"
    }

    def "refuses a body or a parameter asking again does not take, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sendingBody(put(ADDRESS + "/2" + query), body)

        then:
        answered.response.status == 400
        refusedUnasked(answered, code)

        where:
        query    | body || code
        ""       | "{}" || "BODY_UNUSABLE"
        "?now=1" | ""   || "PARAMETER_UNKNOWN"
    }

    def "refuses a member whose roles there do not reach the act, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sendingBody(put(ADDRESS + "/1/review"), reviewing([summary: [outcome: "assured"]]))

        then:
        answered.response.status == 403
        refusedUnasked(answered, "ACT_NOT_PERMITTED")
    }
}
