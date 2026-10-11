package com.lilradish.lite.app.run

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.filling.FillFieldAnswer
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.pool.PersonAnswer
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.run.RunId
import com.lilradish.lite.domain.run.WorkflowStepId
import com.lilradish.lite.web.GroupMembershipRequired
import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory

/**
 * With a replaced store, real HTTP dispatch omits optional absent parts but sends required unknown resource
 * facts and a value of nothing as null.
 */
@WebMvcTest([RunStepsController, StepActsController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class RunStepsControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final JsonNodeFactory NODES = JsonNodeFactory.instance

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000001501")

    static final UUID RUN = UUID.fromString("00000008-0000-4000-8000-000000001501")

    static final UUID VERSION = UUID.fromString("00000007-0000-4000-8000-000000001501")

    static final UUID QUESTION = UUID.fromString("00000006-0000-4000-8000-000000001502")

    static final UUID QUESTION_VERSION = UUID.fromString("00000007-0000-4000-8000-000000001502")

    static final UUID STEP = UUID.fromString("00000009-0000-4000-8000-000000001501")

    static final String ADDRESS = "/api/groups/${GROUP}/runs/${RUN}/steps"

    static final StepAnswers.HeaderAnswer HEADER = new StepAnswers.HeaderAnswer(RUN, 3, VERSION, "running",
            new RunsController.AtAnswer(STEP, "summarise", null), ["stop", "rename"], new StepAnswers.ProgressAnswer(0, 1))

    static final Map<String, StepAnswers.DeclaredAnswer> DECLARED = [(QUESTION_VERSION.toString()):
            new StepAnswers.DeclaredAnswer(
                    [new FillFieldAnswer("text", "Ticket", null, "text", 4000, null, true, null, null, null, null, "several")],
                    [new FillFieldAnswer("summary", null, "One line.", "text", 1000, null, true, null, null, null, null, "several")])]

    static final StepAnswers.FromAnswer FROM_TICKET =
            new StepAnswers.FromAnswer("run_input", "ticket", null, null, null, null, null, null, null, null)

    static final StepAnswers.StepRowAnswer ROW = new StepAnswers.StepRowAnswer(STEP, 1, "summarise", null,
            new StepAnswers.RunsAnswer("question", QUESTION, "Summarise", 2, QUESTION_VERSION, null, null, null, null), null,
            new StepAnswers.WhoAnswer("person", null, null, null), new StepAnswers.WhoAnswer("person", null, null, null),
            "waiting",
            new StepAnswers.WhereAnswer("waiting_on_review", null, null, null, null, null, 1, [new StepAnswers.WaitingValueAnswer("summary", "review_at_gate")], null, null, null, "2026-09-26T09:00:00Z", null, null, "review_at_gate", null, null, null, null, null, null, null),
            [new StepAnswers.TakesFromAnswer("text", FROM_TICKET)],
            new StepAnswers.TriesAnswer(1, 2, false), new StepAnswers.CostAnswer(false, null, null, null, null, null),
            [new StepAnswers.ShownValueAnswer("summary", NODES.stringNode("A printer fire."), null, "waiting_on_review",
                    null)],
            [new StepAnswers.WentInAnswer("text", FROM_TICKET, NODES.nullNode(), null, null, null)],
            "try", null, ["review"], [], null, null, null, null, null, null)

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
    }

    private MvcResult reading(String address) {
        mockMvc.perform(get(address)).andReturn()
    }

    def "every read of a run's steps is asked of membership alone"() {
        given:
        def asked = mappings.handlerMethods.values().findAll { it.beanType == RunStepsController }

        expect:
        asked*.method*.name.toSorted() == ["step", "steps", "tryDetails"]
        asked.every { it.getMethodAnnotation(GroupMembershipRequired) != null }
    }

    def "one try GET dispatches only to its exact numbered material read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(steps.tryDetails(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 2, READER))
                .willReturn(new StepAnswers.TryDetailsRecord(2, null, "", null, null,
                new StepAnswers.ModelReplyRecord("", false, false), null, null))

        when:
        def answered = reading("${ADDRESS}/${STEP}/tries/2")

        then:
        answered.response.status == 200
        JSON.readTree(answered.response.contentAsString) == JSON.readTree(
                '{"number":2,"returned":"","modelReply":{"text":"","altered":false,"truncated":false}}')

        and:
        tryReadOnce()
    }

    private void tryReadOnce() {
        Mockito.verify(steps).tryDetails(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 2, READER)
        Mockito.verifyNoMoreInteractions(steps)
        Mockito.verifyNoInteractions(acts)
    }

    def "one try GET rejects a noncanonical spent number without reading or changing a try"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = reading("${ADDRESS}/${STEP}/tries/${number}")

        then:
        answered.response.status == 404
        JSON.readTree(answered.response.contentAsString).path('code').asText() == 'STEP_NOT_IN_VIEW'
        Mockito.verifyNoInteractions(steps, acts)

        where:
        number << ['0', '01', '-1', '1.0', 'one', '1000000000', '١']
    }

    def "answers every step of a run from one read, the run's header and what it gave back beside them"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        given(steps.steps(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new StepAnswers.RunStepsAnswer(
                HEADER, DECLARED, new StepAnswers.GaveBackAnswer("values", []), [ROW], RunSteps.REREAD_AFTER_SECONDS))

        when:
        def answered = reading(ADDRESS)

        then:
        answered.response.status == 200
        JSON.readTree(answered.response.contentAsString) == JSON.readTree("""
                {"run":{"runId":"${RUN}","number":3,"versionId":"${VERSION}","state":"running",
                        "at":{"stepId":"${STEP}","name":"summarise"},"stateActs":["stop","rename"],
                        "progress":{"done":0,"of":1}},
                 "declarations":{"${QUESTION_VERSION}":{
                     "takes":[{"name":"text","label":"Ticket","kind":"text","longest":4000,"mustBeGiven":true,"lines":"several"}],
                     "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true,"lines":"several"}]}},
                 "gaveBack":{"declares":"values","standing":[]},
                 "steps":[{"stepId":"${STEP}","order":1,"name":"summarise",
                           "runs":{"kind":"question","entryId":"${QUESTION}","name":"Summarise","version":2,
                                   "versionId":"${QUESTION_VERSION}","versionName":null},
                           "producer":{"kind":"person"},"reviewer":{"kind":"person"},"state":"waiting",
                           "where":{"kind":"waiting_on_review","number":1,
                                    "values":[{"field":"summary","on":"review_at_gate"}],
                                    "since":"2026-09-26T09:00:00Z","waitsOn":"review_at_gate"},
                           "takesFrom":[{"input":"text","from":{"kind":"run_input","path":"ticket"}}],
                           "tries":{"current":1,"declared":2,"beyond":false},"cost":{"callsAModel":false},
                           "gaveBack":[{"field":"summary","value":"A printer fire.","now":"waiting_on_review"}],
                           "wentIn":[{"input":"text","from":{"kind":"run_input","path":"ticket"},"value":null}],
                           "wentInFrom":"try","stateActs":["review"],"stateRefusals":[],
                           "producedByUserId":null,"producedByKind":null}],
                 "rereadAfterSeconds":5}
                """ as String)

        and:
        readOnce()
    }

    def "the dispatcher preserves mandatory unknown state keys distinctly from known empty offers"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))
        def header = new StepAnswers.HeaderAnswer(RUN, 3, VERSION, "running", HEADER.at(), offers,
                HEADER.progress())
        def row = new StepAnswers.StepRowAnswer(ROW.stepId(), ROW.order(), ROW.name(), ROW.label(), ROW.runs(),
                ROW.beneath(), ROW.producer(), ROW.reviewer(), ROW.state(), ROW.where(), ROW.takesFrom(),
                ROW.tries(), ROW.cost(), ROW.gaveBack(), ROW.wentIn(), ROW.wentInFrom(), ROW.next(),
                offers, refusals, null, null, null, null, null, null)
        given(steps.steps(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new StepAnswers.RunStepsAnswer(
                header, DECLARED, new StepAnswers.GaveBackAnswer("values", []), [row], null))

        when:
        def answered = reading(ADDRESS)
        def json = JSON.readTree(answered.response.contentAsString)

        then:
        answered.response.status == 200
        json.get("run").has("stateActs")
        json.get("run").get("stateActs") == JSON.readTree(expected)
        def published = json.get("steps").get(0)
        published.has("stateActs")
        published.get("stateActs") == JSON.readTree(expected)
        published.has("stateRefusals")
        published.get("stateRefusals") == JSON.readTree(expected)
        published.has("producedByUserId")
        published.get("producedByUserId").isNull()
        published.has("producedByKind")
        published.get("producedByKind").isNull()
        !published.has("acts")
        !published.has("withheld")

        and:
        readOnce()

        where:
        offers | refusals || expected
        null | null     || "null"
        []   | []       || "[]"
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void readOnce() {
        Mockito.verify(steps).steps(new GroupId(GROUP), new RunId(RUN), READER)
        Mockito.verifyNoMoreInteractions(steps)
        Mockito.verifyNoInteractions(acts)
    }

    def "answers one step's page: every try as it ended, and what answering it here puts to the reader"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def tried = new StepAnswers.TryAnswer(1,
                false,
                null,
                new StepAnswers.WhoAnswer("person", new PersonAnswer("000002", "Cat"), null, null),
                "Read it twice.",
                [new StepAnswers.TriedValueAnswer("summary", NODES.stringNode("A fire."), null, "refused", null,
                        new StepAnswers.DecisionAnswer("refused", "Too short.", null, null, null, null), null)],
                new StepAnswers.ReviewAnswer(true,
                new StepAnswers.WhoAnswer("person", new PersonAnswer("000003", null),
                        null, null),
                null,
                null,
                null,
                false,
                false,
                false),
                "refused_on_review",
                null,
                null,
                false,
                null,
                false,
                null,
                null,
                new StepAnswers.CostAnswer(false, null, null, null, null, null),
                null,
                false,
                false,
                false)
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, ROW,
                        [new StepAnswers.WentInAnswer("text", FROM_TICKET, NODES.stringNode("Fire."), null, null, null)],
                        "try",
                        [new StepAnswers.CameOutAnswer("summary", null, "refused", null, null, null)],
                        [tried],
                        new StepAnswers.AnsweringAnswer(2, false, "Say what the ticket is about.", DECLARED[QUESTION_VERSION.toString()].gives(), new StepAnswers.LastRefusedAnswer(1, tried.values())),
                        "# {{text}}", RunSteps.REREAD_AFTER_SECONDS))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("triesMade") == JSON.readTree("""
                [{"number":1,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"person","person":{"userId":"000002","displayName":"Cat"}},
                  "why":"Read it twice.",
                  "values":[{"field":"summary","value":"A fire.","now":"refused",
                             "decision":{"outcome":"refused","why":"Too short.","whyLines":"several"}}],
                  "review":{"asked":true,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"by":{"kind":"person","person":{"userId":"000003"}}},
                  "ended":"refused_on_review","cost":{"callsAModel":false}}]""")
        answered.get("cameOut") == JSON.readTree('[{"field":"summary","now":"refused"}]')
        answered.get("wentIn") == JSON.readTree('[{"input":"text","from":{"kind":"run_input","path":"ticket"},"value":"Fire."}]')
        answered.get("wentInFrom").asString() == "try"
        answered.get("drawing").asString() == "# {{text}}"
        answered.get("answering") == JSON.readTree("""
                {"number":2,"beyond":false,"instruction":"Say what the ticket is about.",
                 "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true,"lines":"several"}],
                 "lastRefused":{"number":1,"values":[{"field":"summary","value":"A fire.","now":"refused",
                                "decision":{"outcome":"refused","why":"Too short.","whyLines":"several"}}]}}""")

        and: "the run and the step beside it, from the same read, and when to read it again"
        answered.get("run").get("state").asString() == "running"
        answered.get("step").get("stepId").asString() == STEP.toString()
        answered.get("rereadAfterSeconds").asInt() == RunSteps.REREAD_AFTER_SECONDS
    }

    /** A code step tells nothing, so its answering carries no instruction at all rather than an empty one. */
    def "answers what answering a code step here puts to the reader without an instruction"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, ROW, null, null, null, [],
                        new StepAnswers.AnsweringAnswer(2, true, null, DECLARED[QUESTION_VERSION.toString()].gives(), null),
                        null, null))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("answering") == JSON.readTree("""
                {"number":2,"beyond":true,
                 "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true,"lines":"several"}]}""")

        and: "not read again by itself, the run not running"
        !answered.has("rereadAfterSeconds")
        !answered.has("drawing")
    }

    /**
     * Why is published as a word for the page, while code's original evidence is read only by its numbered GET;
     * a member named with nothing is named so, NON_NULL leaving out only what is none.
     */
    def "answers each try of code gone wrong with this system's reason and what it is about, or with what the code said"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def code = new StepAnswers.WhoAnswer("code", null, null, null)
        def nothing = new StepAnswers.ReviewAnswer(false,
                null,
                null,
                null,
                null,
                false,
                false,
                false)
        def free = new StepAnswers.CostAnswer(false, null, null, null, null, null)
        def erredFor = { int number, StepAnswers.ErroredForAnswer why, String returned ->
            new StepAnswers.TryAnswer(number,
                false,
                null,
                code,
                null,
                [],
                nothing,
                "errored",
                null,
                null,
                false,
                why,
                (returned) != null,
                null,
                null,
                free,
                null,
                false,
                false,
                false)
        }
        (1..8).each { number ->
            given(steps.tryDetails(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), number, READER))
                    .willReturn(new StepAnswers.TryDetailsRecord(number,
                    number == 7 ? new StepAnswers.WentWrongAnswer("The mail server refused it.", true, null) : null,
                    number == 1 ? '{"lines":[]}' : null, null, null, null, null, null))
        }
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, ROW, null, null, null, [
                        erredFor(1, new StepAnswers.ErroredForAnswer("too_long", "lines.sku", null, null, null, null),
                                '{"lines":[]}'),
                        erredFor(2, new StepAnswers.ErroredForAnswer("not_declared", null, "extra", null, null, null),
                                null),
                        erredFor(3, new StepAnswers.ErroredForAnswer("not_declared", "lines", "", null, null, null),
                                null),
                        erredFor(4, new StepAnswers.ErroredForAnswer("unkeepable", "note", null, null, null, true),
                                null),
                        erredFor(5, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null, null,
                                new StepAnswers.ReadByAnswer(STEP, null), null), null),
                        erredFor(6, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null, null,
                                new StepAnswers.ReadByAnswer(null, "result.receipt"), null), null),
                        new StepAnswers.TryAnswer(7,
                false,
                null,
                code,
                null,
                [],
                nothing,
                "errored",
                null,
                null,
                true,
                null,
                false,
                null,
                null,
                free,
                null,
                false,
                false,
                false),
                        erredFor(8, new StepAnswers.ErroredForAnswer("not_declared", "lines", "n" * 64, true, null,
                                null), null)],
                        null, null, null))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("triesMade") == JSON.readTree("""
                [{"number":1,"beyond":false,"wentWrongPresent":false,"returnedPresent":true,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored","erroredFor":{"reason":"too_long","field":"lines.sku"},
                  "cost":{"callsAModel":false}},
                 {"number":2,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored","erroredFor":{"reason":"not_declared","member":"extra"},
                  "cost":{"callsAModel":false}},
                 {"number":3,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored","erroredFor":{"reason":"not_declared","field":"lines","member":""},
                  "cost":{"callsAModel":false}},
                 {"number":4,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored","erroredFor":{"reason":"unkeepable","field":"note","returnedNotKept":true},
                  "cost":{"callsAModel":false}},
                 {"number":5,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored","erroredFor":{"reason":"gives_otherwise","field":"receipt","readBy":{"step":"${STEP}"}},
                  "cost":{"callsAModel":false}},
                 {"number":6,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored",
                  "erroredFor":{"reason":"gives_otherwise","field":"receipt","readBy":{"output":"result.receipt"}},
                  "cost":{"callsAModel":false}},
                 {"number":7,"beyond":false,"wentWrongPresent":true,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored",
                  "cost":{"callsAModel":false}},
                 {"number":8,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},
                  "ended":"errored",
                  "erroredFor":{"reason":"not_declared","field":"lines","member":"${"n" * 64}","memberCut":true},
                  "cost":{"callsAModel":false}}]""")

        and: "a member's name said to be cut only where it was, and one not cut saying nothing of it"
        answered.get("triesMade").findAll { it.path("erroredFor").has("member") }
                .collect { it.path("erroredFor").has("memberCut") } == [false, false, true]

        when:
        def evidence = (1..8).collect { number ->
            def response = reading("${ADDRESS}/${STEP}/tries/${number}").response
            assert response.status == 200
            JSON.readTree(response.contentAsString)
        }

        then:
        evidence[0] == JSON.readTree('{"number":1,"returned":"{\\"lines\\":[]}"}')
        evidence[6] == JSON.readTree(
                '{"number":7,"wentWrong":{"detail":"The mail server refused it.","cut":true}}')
        evidence.findAll { it.path('number').asInt() != 1 && it.path('number').asInt() != 7 }
                .every { it.propertyNames().toSet() == ['number'] as Set }
    }

    /**
     * A model's step as it goes out: held on a model that turned it away with what may be spent used up, each
     * turnaway and what was said in it, why a try did not fit, what its call said went wrong, and an input from a
     * code step by that step's name, whose declarations are held under it.
     */
    def "answers a model's step with why it is held, each turnaway, why a try did not fit, and what a call said went wrong"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def turnaway = new StepAnswers.TurnawayAnswer("2026-09-26T09:01:00Z", "Spent.", false, null, false)
        def withheld = new StepAnswers.TurnawayAnswer("2026-09-26T09:00:00Z", null, null, true, true)
        def fromCode = new StepAnswers.FromAnswer("step", "answer", STEP, "tidy_up", null, null, "tidy", null, null, null)
        def row = new StepAnswers.StepRowAnswer(STEP, 2, "summarise", null,
                new StepAnswers.RunsAnswer("question", QUESTION, "Summarise", 2, QUESTION_VERSION, null, null, null, null), null,
                new StepAnswers.WhoAnswer("model", null, "general", null), null, "held_back",
                new StepAnswers.WhereAnswer("held_back", "turned_away", true, null, null, null, null, null, null, null,
                        null, "2026-09-26T09:01:00Z", null, [withheld, turnaway], "starter", null, null, null, null, null, null, null),
                [new StepAnswers.TakesFromAnswer("text", fromCode)], new StepAnswers.TriesAnswer(3, 3, false),
                new StepAnswers.CostAnswer(true, "30", "0", "30", true, "wholly"), null, null, null, null, [], [], null, null, null, null, null, null)
        def misfit = new StepAnswers.TryAnswer(1,
                false,
                null,
                new StepAnswers.WhoAnswer("model", null, "general", null),
                null,
                [],
                new StepAnswers.ReviewAnswer(false,
                null,
                null,
                null,
                null,
                false,
                false,
                false),
                "did_not_fit",
                "cut_off",
                null,
                false,
                null,
                false,
                null,
                null,
                new StepAnswers.CostAnswer(true, "10", "0", "10", false, null),
                null,
                false,
                false,
                false)
        def wrong = new StepAnswers.TryAnswer(2,
                false,
                null,
                new StepAnswers.WhoAnswer("model", null, "general", null),
                null,
                [],
                new StepAnswers.ReviewAnswer(false,
                null,
                null,
                null,
                null,
                false,
                false,
                false),
                "errored",
                null,
                new StepAnswers.WentWrongAnswer(null, null, true),
                false,
                null,
                false,
                null,
                null,
                new StepAnswers.CostAnswer(true, "10", "0", "10", true, "wholly"),
                null,
                false,
                false,
                false)
        def declared = new LinkedHashMap<String, StepAnswers.DeclaredAnswer>(DECLARED)
        declared.put("tidy", new StepAnswers.DeclaredAnswer([], DECLARED[QUESTION_VERSION.toString()].gives()))
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, declared, row, null, null, null, [misfit, wrong], null,
                        null, RunSteps.REREAD_AFTER_SECONDS))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("step").get("where") == JSON.readTree("""
                {"kind":"held_back","reason":"turned_away","spentUp":true,"since":"2026-09-26T09:01:00Z",
                 "turnedAway":[{"at":"2026-09-26T09:00:00Z","withheld":true,"sentAgain":true},
                               {"at":"2026-09-26T09:01:00Z","said":"Spent.","cut":false,"sentAgain":false}],
                 "waitsOn":"starter"}""")
        answered.get("step").get("takesFrom") == JSON.readTree("""
                [{"input":"text","from":{"kind":"step","path":"answer","stepId":"${STEP}","name":"tidy_up",
                                         "codeStep":"tidy"}}]""" as String)
        answered.get("declarations").get("tidy") == JSON.readTree("""
                {"takes":[],
                 "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true,"lines":"several"}]}""")

        and: "no reviewer where every field it gives back stands as given"
        !answered.get("step").has("reviewer")

        and: "which way a try did not fit, and what a call said went wrong withheld, as each goes out"
        answered.get("triesMade") == JSON.readTree("""
                [{"number":1,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"model","model":"general"},"values":[],
                  "review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},"ended":"did_not_fit","didNotFit":"cut_off",
                  "cost":{"callsAModel":true,"sent":"10","cameBack":"0","spent":"10","cameBackUnknown":false}},
                 {"number":2,"beyond":false,"wentWrongPresent":false,"returnedPresent":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false,"producedBy":{"kind":"model","model":"general"},"values":[],
                  "review":{"asked":false,"declinedPresent":false,"unreadableResponsePresent":false,"modelReplyPresent":false},"ended":"errored","wentWrong":{"withheld":true},
                  "cost":{"callsAModel":true,"sent":"10","cameBack":"0","spent":"10","cameBackUnknown":true,
                          "measuredHere":"wholly"}}]""")
    }

    def "a declined model call reads two kept blocks by number and omits both where withheld"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def model = new StepAnswers.WhoAnswer("model", null, "general", null)
        def noReview = new StepAnswers.ReviewAnswer(false,
                null,
                null,
                null,
                null,
                false,
                false,
                false)
        def cost = new StepAnswers.CostAnswer(true, "10", "0", "10", false, null)
        def shown = new StepAnswers.TryAnswer(1,
                false,
                null,
                model,
                null,
                [],
                noReview,
                "did_not_fit",
                "model_declined",
                null,
                false,
                null,
                false,
                null,
                null,
                cost,
                null,
                true,
                false,
                true)
        def withheld = new StepAnswers.TryAnswer(2,
                false,
                null,
                model,
                null,
                [],
                noReview,
                "did_not_fit",
                "model_declined",
                null,
                false,
                null,
                false,
                null,
                null,
                cost,
                null,
                false,
                false,
                false)
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, ROW, null, null, null, [shown, withheld], null,
                        null, RunSteps.REREAD_AFTER_SECONDS))
        given(steps.tryDetails(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 1, READER))
                .willReturn(new StepAnswers.TryDetailsRecord(1, null, null,
                new StepAnswers.DeclinedAnswer(
                        new StepAnswers.DeclinedTextAnswer("Demo Sample refusal", true, false),
                        new StepAnswers.DeclinedTextAnswer("Demo Sample answer", true, true), null),
                null, null, null, null))
        given(steps.tryDetails(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 2, READER))
                .willReturn(new StepAnswers.TryDetailsRecord(2, null, null, null, null, null, null, null))

        when:
        def tries = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString).get("triesMade")
        def first = JSON.readTree(reading("${ADDRESS}/${STEP}/tries/1").response.contentAsString)
        def second = JSON.readTree(reading("${ADDRESS}/${STEP}/tries/2").response.contentAsString)

        then:
        tries*.has('declined') == [false, false]
        tries.collect { it.path('declinedPresent').asBoolean() } == [true, false]
        tries.collect { it.path('modelReplyPresent').asBoolean() } == [true, false]
        first.get("declined") == JSON.readTree("""
                {"words":{"text":"Demo Sample refusal","altered":true,"truncated":false},"wordsLines":"several",
                 "content":{"text":"Demo Sample answer","altered":true,"truncated":true}}""")
        !first.has('modelReply')
        second == JSON.readTree('{"number":2}')
        !second.has('words')
        !second.has('content')
        !second.has('wordsLines')
    }

    def "the dispatcher sends unreadable evidence with four exact members beside the call it belongs to"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def model = new StepAnswers.WhoAnswer("model", null, "general", null)
        def evidence = new StepAnswers.UnreadableResponseRecord("Imaginary service prefix", false, false, stopped)
        def review = new StepAnswers.ReviewAnswer(reviewing,
                reviewing ? model : null,
                reviewing ? true : null,
                reviewing ? "errored" : null,
                null,
                false,
                reviewing,
                false)
        def tried = new StepAnswers.TryAnswer(1,
                false,
                null,
                model,
                null,
                [],
                review,
                "errored",
                null,
                null,
                false,
                null,
                false,
                null,
                null,
                new StepAnswers.CostAnswer(true, "10", "0", "10", true, "wholly"),
                null,
                false,
                !reviewing,
                false)
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, ROW, null, null, null, [tried], null, null, null))
        given(steps.tryDetails(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 1, READER))
                .willReturn(new StepAnswers.TryDetailsRecord(1, null, null, null,
                reviewing ? null : evidence, null,
                reviewing ? new StepAnswers.ReviewDetailsRecord(null, evidence, null) : null, null))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString).get("triesMade")[0]
        def material = JSON.readTree(reading("${ADDRESS}/${STEP}/tries/1").response.contentAsString)
        def owner = reviewing ? material.get("review") : material

        then:
        owner.get("unreadableResponse") == JSON.readTree("""
                {"text":"Imaginary service prefix","altered":false,"truncated":false,"stopped":${stopped}}
                """)
        (reviewing ? material : material.path("review")).has("unreadableResponse") == false
        !answered.has('unreadableResponse')
        !answered.path('review').has('unreadableResponse')
        answered.path('unreadableResponsePresent').asBoolean() == !reviewing
        answered.path('review').path('unreadableResponsePresent').asBoolean() == reviewing

        where:
        reviewing | stopped
        false     | false
        false     | true
        true      | false
        true      | true
    }

    def "the dispatcher sends search records, answering instruction and the held reason"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def question = "**Imaginary invoice evidence**"
        def where = new StepAnswers.WhereAnswer("held_back", "turned_away", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
        def row = new StepAnswers.StepRowAnswer(ROW.stepId(), ROW.order(), ROW.name(), ROW.label(), ROW.runs(), ROW.beneath(),
                ROW.producer(), ROW.reviewer(), "held_back", where, ROW.takesFrom(), ROW.tries(), ROW.cost(),
                ROW.gaveBack(), ROW.wentIn(), ROW.wentInFrom(), ROW.next(), ROW.stateActs(), ROW.stateRefusals(), ROW.producedByUserId(), ROW.producedByKind(), ROW.givesOtherwise(), ROW.letGoThen(), ROW.waitsOnStarter(), ROW.length())
        def search = new StepAnswers.SearchAnswer(null,
                [new StepAnswers.SearchedAnswer("*Imaginary invoice query*", "Imaginary paper ledger")],
                null, null, null, null, null, null, null, null)
        def tried = new StepAnswers.TryAnswer(1,
                false,
                null,
                new StepAnswers.WhoAnswer("model", null, "general", null),
                null,
                [],
                new StepAnswers.ReviewAnswer(false,
                null,
                null,
                null,
                null,
                false,
                false,
                false),
                "stands",
                null,
                null,
                false,
                null,
                false,
                null,
                null,
                new StepAnswers.CostAnswer(true, "0", "0", "0", false, null),
                new StepAnswers.SearchCountAnswer(null, null, null, null),
                false,
                false,
                false)
        def answering = new StepAnswers.AnsweringAnswer(2, false, question, [], null)
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, row, null, null, null, [tried], answering, null, null))
        given(steps.tryDetails(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), 1, READER))
                .willReturn(new StepAnswers.TryDetailsRecord(1, null, null, null, null, null, null, search))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("step").get("where") == JSON.readTree(
                '{"kind":"held_back","reason":"turned_away"}')
        answered.get("answering") == JSON.readTree(
                '{"number":2,"beyond":false,"instruction":"**Imaginary invoice evidence**","gives":[]}')
        answered.get("triesMade")[0].get("search") == JSON.readTree('{}')

        when:
        def material = JSON.readTree(reading("${ADDRESS}/${STEP}/tries/1").response.contentAsString)

        then:
        material.get("search") == JSON.readTree(
                '{"searches":[{"searchedFor":"*Imaginary invoice query*","found":"Imaginary paper ledger"}]}')
    }

    def "a failure for a model not held goes out naming the model, its mode, and that it was to review"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def row = new StepAnswers.StepRowAnswer(STEP, 1, "summarise", null,
                new StepAnswers.RunsAnswer("question", QUESTION, "Summarise", 2, QUESTION_VERSION, null, null, null, null), null,
                new StepAnswers.WhoAnswer("person", null, null, null),
                new StepAnswers.WhoAnswer("model", null, "small", "research"), "failed",
                new StepAnswers.WhereAnswer("failed", "model_not_deployed", null, "small", "research", true, null, null, null, null, null, "2026-09-26T09:00:00Z", null, null, "starter", null, null, null, null, null, null, null),
                [], new StepAnswers.TriesAnswer(1, 2, false), new StepAnswers.CostAnswer(true, "0", "0", "0", false, null),
                null, null, null, null, [], [], null, null, null, null, null, null)
        given(steps.steps(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new StepAnswers.RunStepsAnswer(
                HEADER, DECLARED, new StepAnswers.GaveBackAnswer("values", []), [row], null))

        when:
        def answered = JSON.readTree(reading(ADDRESS).response.contentAsString)

        then:
        answered.get("steps").get(0).get("where") == JSON.readTree("""
                {"kind":"failed","reason":"model_not_deployed","model":"small","mode":"research","reviewing":true,
                 "since":"2026-09-26T09:00:00Z","waitsOn":"starter"}""")
        !answered.has("rereadAfterSeconds")
    }

    /** A reader refuses a failure whose reason and what it names disagree, so the value nothing gave goes out as null. */
    def "a failure on a value no case claimed, or on going too deep, goes out naming that alone, a value not given as null and one withheld as withheld only"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def row = new StepAnswers.StepRowAnswer(STEP, 1, "summarise", null,
                new StepAnswers.RunsAnswer("question", QUESTION, "Summarise", 2, QUESTION_VERSION, null, null, null, null), null,
                new StepAnswers.WhoAnswer("person", null, null, null), null, "failed",
                new StepAnswers.WhereAnswer("failed", reason, null, null, null, null, null, null, null, null, null, "2026-09-26T09:00:00Z", null, null, "starter", null, unclaimed, tooDeep, null, null, null, null),
                [], new StepAnswers.TriesAnswer(0, 1, false), new StepAnswers.CostAnswer(false, "0", "0", "0", false, null),
                null, null, null, null, [], [], null, null, null, null, null, null)
        given(steps.steps(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new StepAnswers.RunStepsAnswer(
                HEADER, DECLARED, new StepAnswers.GaveBackAnswer("values", []), [row], null))

        when:
        def answered = JSON.readTree(reading(ADDRESS).response.contentAsString)

        then:
        answered.get("steps").get(0).get("where") == JSON.readTree("""
                {"kind":"failed","reason":"${reason}","since":"2026-09-26T09:00:00Z","waitsOn":"starter",${named}}""")

        where:
        reason            | unclaimed                                       | tooDeep                             || named
        "unclaimed_value" | new StepAnswers.UnclaimedAnswer.Named("wait")   | null                                || '"failedUnclaimed":{"term":"wait"}'
        "unclaimed_value" | new StepAnswers.UnclaimedAnswer.Named(null)     | null                                || '"failedUnclaimed":{"term":null}'
        "unclaimed_value" | new StepAnswers.UnclaimedAnswer.Withheld(true)  | null                                || '"failedUnclaimed":{"withheld":true}'
        "too_deep"        | null                                            | new StepAnswers.TooDeepAnswer("32") || '"failedTooDeep":{"most":"32"}'
    }

    /** A resource refusal names the act; the mismatched field and its reader are separate resource facts. */
    def "a code step giving otherwise names the field and what reads it only where they are named"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def codeRow = { int order, List<StepAnswers.StateRefusalAnswer> refused, StepAnswers.GivesOtherwiseNames fault ->
            new StepAnswers.StepRowAnswer(STEP, order, "send", null, new StepAnswers.RunsAnswer("code_step", null, null, null,
                    null, "send_reply", null, null, null), null, new StepAnswers.WhoAnswer("code", null, null, null), null, "not_started", null,
                    [], null, new StepAnswers.CostAnswer(false, null, null, null, null, null), null, null, null, null, [],
                    refused, null, null, fault, null, null, null)
        }
        def givesOtherwise = new StepAnswers.StateRefusalAnswer("answer", "CODE_STEP_GIVES_OTHERWISE")
        def listNotHere = new StepAnswers.StateRefusalAnswer("answer", "CODE_STEP_GIVES_OTHERWISE")
        def notOffered = new StepAnswers.StateRefusalAnswer("ask_again", "ASK_AGAIN_NOT_OFFERED")
        given(steps.steps(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new StepAnswers.RunStepsAnswer(
                HEADER, [:], new StepAnswers.GaveBackAnswer("values", []),
                [codeRow(1, [givesOtherwise, notOffered],
                         new StepAnswers.GivesOtherwiseNames(new StepAnswers.ReadByAnswer(STEP, null), "receipt")),
                 codeRow(2, [listNotHere], new StepAnswers.GivesOtherwiseNames(null, "lines.grade"))], null))

        when:
        def answered = JSON.readTree(reading(ADDRESS).response.contentAsString)

        then:
        answered.get("steps").get(0).get("stateRefusals") == JSON.readTree("""
                [{"act":"answer","refusal":"CODE_STEP_GIVES_OTHERWISE"},
                 {"act":"ask_again","refusal":"ASK_AGAIN_NOT_OFFERED"}]""")
        answered.get("steps").get(0).get("givesOtherwise") == JSON.readTree("""
                {"readBy":{"step":"${STEP}"},"field":"receipt"}""" as String)
        answered.get("steps").get(1).get("stateRefusals") == JSON.readTree("""
                [{"act":"answer","refusal":"CODE_STEP_GIVES_OTHERWISE"}]""")
        answered.get("steps").get(1).get("givesOtherwise") == JSON.readTree('{"field":"lines.grade"}')
    }

    def "refuses an address naming no run or no step, or a parameter, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = reading(address)

        then:
        answered.response.status == status
        JSON.readTree(answered.response.contentAsString).get("code").asString() == code
        Mockito.verifyNoInteractions(steps, acts)

        where:
        address                                              || status | code
        "/api/groups/${GROUP}/runs/not-a-run/steps"          || 404    | "RUN_NOT_IN_VIEW"
        "/api/groups/${GROUP}/runs/not-a-run/steps/${STEP}"  || 404    | "RUN_NOT_IN_VIEW"
        ADDRESS + "/not-a-step"                              || 404    | "STEP_NOT_IN_VIEW"
        ADDRESS + "?order=1"                                 || 400    | "PARAMETER_UNKNOWN"
        ADDRESS + "/" + STEP + "?tries=all"                  || 400    | "PARAMETER_UNKNOWN"
    }

    def "refuses somebody holding nothing in the group as no group, and never asks the store"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def answered = reading(ADDRESS)

        then:
        answered.response.status == 404
        JSON.readTree(answered.response.contentAsString).get("code").asString() == "GROUP_NOT_IN_VIEW"
        Mockito.verifyNoInteractions(steps, acts)
    }
}
