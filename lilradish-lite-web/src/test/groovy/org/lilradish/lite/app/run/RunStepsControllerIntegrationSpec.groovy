package org.lilradish.lite.app.run

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.filling.FillFieldAnswer
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.pool.PersonAnswer
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.WorkflowStepId
import org.lilradish.lite.web.GroupMembershipRequired
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
 * A run's steps as they go out, over a real dispatcher and a replaced store: what one read of them is written as,
 * every absent part left out rather than sent as null, and a value of nothing sent as null.
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
            new RunsController.AtAnswer(STEP, "summarise"), ["stop", "rename"], new StepAnswers.ProgressAnswer(0, 1))

    static final Map<String, StepAnswers.DeclaredAnswer> DECLARED = [(QUESTION_VERSION.toString()):
            new StepAnswers.DeclaredAnswer(
                    [new FillFieldAnswer("text", "Ticket", null, "text", 4000, null, true, null, null)],
                    [new FillFieldAnswer("summary", null, "One line.", "text", 1000, null, true, null, null)])]

    static final StepAnswers.FromAnswer FROM_TICKET =
            new StepAnswers.FromAnswer("run_input", "ticket", null, null, null, null)

    /** Waiting on a review the reader may give, with what went in drawn beside it. */
    static final StepAnswers.StepRowAnswer ROW = new StepAnswers.StepRowAnswer(STEP, 1, "summarise",
            new StepAnswers.RunsAnswer("question", QUESTION, "Summarise", 2, QUESTION_VERSION, null),
            new StepAnswers.WhoAnswer("person", null, null, null), new StepAnswers.WhoAnswer("person", null, null, null),
            "waiting",
            new StepAnswers.WhereAnswer("waiting_on_review", null, null, null, null, null, 1,
                    [new StepAnswers.WaitingValueAnswer("summary", "review_at_gate")], null, null, null,
                    "2026-09-26T09:00:00Z", null, null, "review_at_gate", null),
            [new StepAnswers.TakesFromAnswer("text", FROM_TICKET)],
            new StepAnswers.TriesAnswer(1, 2, false), new StepAnswers.CostAnswer(false, null, null, null, null, null),
            [new StepAnswers.ShownValueAnswer("summary", NODES.stringNode("A printer fire."), null, "waiting_on_review",
                    null)],
            [new StepAnswers.WentInAnswer("text", FROM_TICKET, NODES.nullNode(), null, null)],
            "try", null, ["review"], [])

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
        asked*.method*.name.toSorted() == ["step", "steps"]
        asked.every { it.getMethodAnnotation(GroupMembershipRequired) != null }
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
                        "at":{"stepId":"${STEP}","name":"summarise"},"acts":["stop","rename"],
                        "progress":{"done":0,"of":1}},
                 "declarations":{"${QUESTION_VERSION}":{
                     "takes":[{"name":"text","label":"Ticket","kind":"text","longest":4000,"mustBeGiven":true}],
                     "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true}]}},
                 "gaveBack":{"declares":"values","standing":[]},
                 "steps":[{"stepId":"${STEP}","order":1,"name":"summarise",
                           "runs":{"kind":"question","entryId":"${QUESTION}","name":"Summarise","version":2,
                                   "versionId":"${QUESTION_VERSION}"},
                           "producer":{"kind":"person"},"reviewer":{"kind":"person"},"state":"waiting",
                           "where":{"kind":"waiting_on_review","number":1,
                                    "values":[{"field":"summary","on":"review_at_gate"}],
                                    "since":"2026-09-26T09:00:00Z","waitsOn":"review_at_gate"},
                           "takesFrom":[{"input":"text","from":{"kind":"run_input","path":"ticket"}}],
                           "tries":{"current":1,"declared":2,"beyond":false},"cost":{"callsAModel":false},
                           "gaveBack":[{"field":"summary","value":"A printer fire.","now":"waiting_on_review"}],
                           "wentIn":[{"input":"text","from":{"kind":"run_input","path":"ticket"},"value":null}],
                           "wentInFrom":"try","acts":["review"],"withheld":[]}],
                 "rereadAfterSeconds":5}
                """ as String)

        and:
        readOnce()
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
        def tried = new StepAnswers.TryAnswer(1, false, null,
                new StepAnswers.WhoAnswer("person", new PersonAnswer("000002", "Cat"), null, null), "Read it twice.",
                [new StepAnswers.TriedValueAnswer("summary", NODES.stringNode("A fire."), null, "refused", null,
                        new StepAnswers.DecisionAnswer("refused", "Too short.", null), null)],
                new StepAnswers.ReviewAnswer(true, new StepAnswers.WhoAnswer("person", new PersonAnswer("000003", null),
                        null, null), null, null, null),
                "refused_on_review", null, null, null, null, null,
                new StepAnswers.CostAnswer(false, null, null, null, null, null))
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, ROW,
                        [new StepAnswers.WentInAnswer("text", FROM_TICKET, NODES.stringNode("Fire."), null, null)],
                        "try",
                        [new StepAnswers.CameOutAnswer("summary", null, "refused")],
                        [tried],
                        new StepAnswers.AnsweringAnswer(2, false, "Say what the ticket is about.",
                                DECLARED[QUESTION_VERSION.toString()].gives(),
                                new StepAnswers.LastRefusedAnswer(1, tried.values())),
                        RunSteps.REREAD_AFTER_SECONDS))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("triesMade") == JSON.readTree("""
                [{"number":1,"beyond":false,"producedBy":{"kind":"person","person":{"userId":"000002","displayName":"Cat"}},
                  "why":"Read it twice.",
                  "values":[{"field":"summary","value":"A fire.","now":"refused",
                             "decision":{"outcome":"refused","why":"Too short."}}],
                  "review":{"asked":true,"by":{"kind":"person","person":{"userId":"000003"}}},
                  "ended":"refused_on_review","cost":{"callsAModel":false}}]""")
        answered.get("cameOut") == JSON.readTree('[{"field":"summary","now":"refused"}]')
        answered.get("wentIn") == JSON.readTree('[{"input":"text","from":{"kind":"run_input","path":"ticket"},"value":"Fire."}]')
        answered.get("wentInFrom").asString() == "try"
        answered.get("answering") == JSON.readTree("""
                {"number":2,"beyond":false,"instruction":"Say what the ticket is about.",
                 "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true}],
                 "lastRefused":{"number":1,"values":[{"field":"summary","value":"A fire.","now":"refused",
                                "decision":{"outcome":"refused","why":"Too short."}}]}}""")

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
                        new StepAnswers.AnsweringAnswer(2, true, null, DECLARED[QUESTION_VERSION.toString()].gives(),
                                null),
                        null))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("answering") == JSON.readTree("""
                {"number":2,"beyond":true,
                 "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true}]}""")

        and: "not read again by itself, the run not running"
        !answered.has("rereadAfterSeconds")
    }

    /**
     * Why is published as a word for the page to put in its reader's words, and what the code said as it said it; a
     * member named with nothing is named so, NON_NULL leaving out only what is none.
     */
    def "answers each try of code gone wrong with this system's reason and what it is about, or with what the code said"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def code = new StepAnswers.WhoAnswer("code", null, null, null)
        def nothing = new StepAnswers.ReviewAnswer(false, null, null, null, null)
        def free = new StepAnswers.CostAnswer(false, null, null, null, null, null)
        def erredFor = { int number, StepAnswers.ErroredForAnswer why, String returned ->
            new StepAnswers.TryAnswer(number, false, null, code, null, [], nothing, "errored", null, null, why, returned,
                    null, free)
        }
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, DECLARED, ROW, null, null, null, [
                        erredFor(1, new StepAnswers.ErroredForAnswer("too_long", "lines.sku", null, null, null),
                                '{"lines":[]}'),
                        erredFor(2, new StepAnswers.ErroredForAnswer("not_declared", null, "extra", null, null), null),
                        erredFor(3, new StepAnswers.ErroredForAnswer("not_declared", "lines", "", null, null), null),
                        erredFor(4, new StepAnswers.ErroredForAnswer("unkeepable", "note", null, null, true), null),
                        erredFor(5, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null,
                                new StepAnswers.ReadByAnswer(STEP, null), null), null),
                        erredFor(6, new StepAnswers.ErroredForAnswer("gives_otherwise", "receipt", null,
                                new StepAnswers.ReadByAnswer(null, "result.receipt"), null), null),
                        new StepAnswers.TryAnswer(7, false, null, code, null, [], nothing, "errored", null,
                                new StepAnswers.WentWrongAnswer("The mail server refused it.", true, null), null, null,
                                null, free)],
                        null, null))

        when:
        def answered = JSON.readTree(reading(ADDRESS + "/" + STEP).response.contentAsString)

        then:
        answered.get("triesMade") == JSON.readTree("""
                [{"number":1,"beyond":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false},
                  "ended":"errored","erroredFor":{"reason":"too_long","field":"lines.sku"},"returned":"{\\"lines\\":[]}",
                  "cost":{"callsAModel":false}},
                 {"number":2,"beyond":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false},
                  "ended":"errored","erroredFor":{"reason":"not_declared","member":"extra"},
                  "cost":{"callsAModel":false}},
                 {"number":3,"beyond":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false},
                  "ended":"errored","erroredFor":{"reason":"not_declared","field":"lines","member":""},
                  "cost":{"callsAModel":false}},
                 {"number":4,"beyond":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false},
                  "ended":"errored","erroredFor":{"reason":"unkeepable","field":"note","returnedNotKept":true},
                  "cost":{"callsAModel":false}},
                 {"number":5,"beyond":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false},
                  "ended":"errored","erroredFor":{"reason":"gives_otherwise","field":"receipt","readBy":{"step":"${STEP}"}},
                  "cost":{"callsAModel":false}},
                 {"number":6,"beyond":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false},
                  "ended":"errored",
                  "erroredFor":{"reason":"gives_otherwise","field":"receipt","readBy":{"output":"result.receipt"}},
                  "cost":{"callsAModel":false}},
                 {"number":7,"beyond":false,"producedBy":{"kind":"code"},"values":[],"review":{"asked":false},
                  "ended":"errored","wentWrong":{"detail":"The mail server refused it.","cut":true},
                  "cost":{"callsAModel":false}}]""")
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
        def fromCode = new StepAnswers.FromAnswer("step", "answer", STEP, "tidy_up", null, "tidy")
        def row = new StepAnswers.StepRowAnswer(STEP, 2, "summarise",
                new StepAnswers.RunsAnswer("question", QUESTION, "Summarise", 2, QUESTION_VERSION, null),
                new StepAnswers.WhoAnswer("model", null, "general", null), null, "held_back",
                new StepAnswers.WhereAnswer("held_back", "turned_away", true, null, null, null, null, null, null, null,
                        null, "2026-09-26T09:01:00Z", null, [withheld, turnaway], "starter", null),
                [new StepAnswers.TakesFromAnswer("text", fromCode)], new StepAnswers.TriesAnswer(3, 3, false),
                new StepAnswers.CostAnswer(true, "30", "0", "30", true, true), null, null, null, null, [], [])
        def misfit = new StepAnswers.TryAnswer(1, false, null, new StepAnswers.WhoAnswer("model", null, "general", null),
                null, [], new StepAnswers.ReviewAnswer(false, null, null, null, null), "did_not_fit", "cut_off", null,
                null, null, null, new StepAnswers.CostAnswer(true, "10", "0", "10", false, null))
        def wrong = new StepAnswers.TryAnswer(2, false, null, new StepAnswers.WhoAnswer("model", null, "general", null),
                null, [], new StepAnswers.ReviewAnswer(false, null, null, null, null), "errored", null,
                new StepAnswers.WentWrongAnswer(null, null, true), null, null, null,
                new StepAnswers.CostAnswer(true, "10", "0", "10", true, true))
        def declared = new LinkedHashMap<String, StepAnswers.DeclaredAnswer>(DECLARED)
        declared.put("tidy", new StepAnswers.DeclaredAnswer([], DECLARED[QUESTION_VERSION.toString()].gives()))
        given(steps.step(new GroupId(GROUP), new RunId(RUN), new WorkflowStepId(STEP), READER)).willReturn(
                new StepAnswers.StepAnswer(HEADER, declared, row, null, null, null, [misfit, wrong], null,
                        RunSteps.REREAD_AFTER_SECONDS))

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
                 "gives":[{"name":"summary","help":"One line.","kind":"text","longest":1000,"mustBeGiven":true}]}""")

        and: "no reviewer where every field it gives back stands as given"
        !answered.get("step").has("reviewer")

        and: "which way a try did not fit, and what a call said went wrong withheld, as each goes out"
        answered.get("triesMade") == JSON.readTree("""
                [{"number":1,"beyond":false,"producedBy":{"kind":"model","model":"general"},"values":[],
                  "review":{"asked":false},"ended":"did_not_fit","didNotFit":"cut_off",
                  "cost":{"callsAModel":true,"sent":"10","cameBack":"0","spent":"10","cameBackUnknown":false}},
                 {"number":2,"beyond":false,"producedBy":{"kind":"model","model":"general"},"values":[],
                  "review":{"asked":false},"ended":"errored","wentWrong":{"withheld":true},
                  "cost":{"callsAModel":true,"sent":"10","cameBack":"0","spent":"10","cameBackUnknown":true,
                          "measuredHere":true}}]""")
    }

    def "a failure for a model not held goes out naming the model, its mode, and that it was to review"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def row = new StepAnswers.StepRowAnswer(STEP, 1, "summarise",
                new StepAnswers.RunsAnswer("question", QUESTION, "Summarise", 2, QUESTION_VERSION, null),
                new StepAnswers.WhoAnswer("person", null, null, null),
                new StepAnswers.WhoAnswer("model", null, "small", "research"), "failed",
                new StepAnswers.WhereAnswer("failed", "model_not_deployed", null, "small", "research", true, null, null,
                        null, null, null, "2026-09-26T09:00:00Z", null, null, "starter", null),
                [], new StepAnswers.TriesAnswer(1, 2, false), new StepAnswers.CostAnswer(true, "0", "0", "0", false, null),
                null, null, null, null, [], [])
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

    /** What a code step giving otherwise is named with goes out only where it is named, never as null. */
    def "an act withheld goes out naming the field and what reads it only where a code step giving otherwise names them"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def codeRow = { int order, List<StepAnswers.WithheldAnswer> withheld ->
            new StepAnswers.StepRowAnswer(STEP, order, "send", new StepAnswers.RunsAnswer("code_step", null, null, null,
                    null, "send_reply"), new StepAnswers.WhoAnswer("code", null, null, null), null, "not_started", null,
                    [], null, new StepAnswers.CostAnswer(false, null, null, null, null, null), null, null, null, null, [],
                    withheld)
        }
        def givesOtherwise = new StepAnswers.WithheldAnswer("answer", "CODE_STEP_GIVES_OTHERWISE",
                new StepAnswers.ReadByAnswer(STEP, null), "receipt")
        def listNotHere = new StepAnswers.WithheldAnswer("answer", "CODE_STEP_GIVES_OTHERWISE", null, "lines.grade")
        def notOffered = new StepAnswers.WithheldAnswer("ask_again", "ASK_AGAIN_NOT_OFFERED", null, null)
        given(steps.steps(new GroupId(GROUP), new RunId(RUN), READER)).willReturn(new StepAnswers.RunStepsAnswer(
                HEADER, [:], new StepAnswers.GaveBackAnswer("values", []),
                [codeRow(1, [givesOtherwise, notOffered]), codeRow(2, [listNotHere])], null))

        when:
        def answered = JSON.readTree(reading(ADDRESS).response.contentAsString)

        then:
        answered.get("steps").get(0).get("withheld") == JSON.readTree("""
                [{"act":"answer","refusal":"CODE_STEP_GIVES_OTHERWISE","readBy":{"step":"${STEP}"},"field":"receipt"},
                 {"act":"ask_again","refusal":"ASK_AGAIN_NOT_OFFERED"}]""" as String)
        answered.get("steps").get(1).get("withheld") == JSON.readTree("""
                [{"act":"answer","refusal":"CODE_STEP_GIVES_OTHERWISE","field":"lines.grade"}]""")
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
