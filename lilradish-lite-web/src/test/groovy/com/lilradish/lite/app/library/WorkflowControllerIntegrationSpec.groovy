package com.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyBoolean
import static org.mockito.ArgumentMatchers.anyInt
import static org.mockito.BDDMockito.given
import static org.mockito.BDDMockito.willThrow
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.model.ModelMode
import com.lilradish.lite.domain.model.ModelName
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentPlace
import com.lilradish.lite.domain.registry.ContentProblem
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryId
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.registry.VersionStanding
import com.lilradish.lite.domain.run.Ceiling
import com.lilradish.lite.domain.search.SearchWay
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.ModelChoice
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.RouteCase
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.testutil.OfferedLists
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.library.DeployedModels
import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper


@WebMvcTest(WorkflowController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class WorkflowControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    /** More digits than a double carries, so a constant rounded on either side of the wire is seen. */
    static final String DIGITS_38 = "12345678901234567890123456789012345678"

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000f31")

    static final UUID ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000f31")

    static final UUID VERSION = UUID.fromString("00000007-0000-4000-8000-000000000f31")

    static final UUID ASKED = UUID.fromString("00000007-0000-4000-8000-000000000f32")

    static final UUID LED = UUID.fromString("00000007-0000-4000-8000-000000000f33")

    static final UUID LED_NEWER = UUID.fromString("00000007-0000-4000-8000-000000000f34")

    static final UUID LIST = UUID.fromString("00000007-0000-4000-8000-000000000f35")

    static final List<UUID> KEYS = (1..8).collect { UUID.fromString("0000000b-0000-4000-8000-000000000f3${it}") }

    static final List<UUID> STEPS = (1..5).collect { UUID.fromString("0000000c-0000-4000-8000-000000000f3${it}") }

    static final UUID PART = UUID.fromString("0000000f-0000-4000-8000-000000000f31")

    static final List<UUID> CASES = (1..2).collect { UUID.fromString("0000000d-0000-4000-8000-000000000f3${it}") }

    static final List<UUID> BINDINGS = (1..4).collect { UUID.fromString("0000000e-0000-4000-8000-000000000f3${it}") }

    static final String AT_VERSION = "/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}"

    static final List<String> FILE_TYPES_65 = (0..<65).collect { ".x${it}" as String }

    static final StoredDeclarations.Half TAKES = half(DeclarationSide.TAKES, [taken("complaint", 4000)], [KEYS[0]])

    static final StoredDeclarations.Half GIVES = half(DeclarationSide.GIVES, [held("summary", new FieldShape.Text(500))], [KEYS[1]])

    static final StoredDeclarations.Half ROUTE_GIVES = half(DeclarationSide.GIVES, [held("note", new FieldShape.Text(100))], [KEYS[2]])

    static final StoredDeclarations.Halves ASKING = new StoredDeclarations.Halves(
            half(DeclarationSide.TAKES, [taken("complaint", 4000)], [KEYS[4]]),
            half(DeclarationSide.GIVES, [held("category", new FieldShape.Term(new EntryVersionId(LIST))),
                                         held("summary", new FieldShape.Text(500))], [KEYS[5], KEYS[6]]))

    static final StoredDeclarations.Halves LEADING = new StoredDeclarations.Halves(
            half(DeclarationSide.TAKES, [taken("complaint", 4000)], [KEYS[7]]), half(DeclarationSide.GIVES, [], []))

    static final StoredDeclarations.Halves NOTHING = new StoredDeclarations.Halves(
            half(DeclarationSide.TAKES, [], []), half(DeclarationSide.GIVES, [], []))


    static final StoredDeclarations.Halves SENDING = StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())


    static final StoredDeclarations.Half SEARCH_GIVES = StoredDeclarations.ofSearchWay(DeployedModels.WEB)

    static final EntryVersionId SEARCH_ASKED = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000f39"))

    static final StoredDeclarations.Halves SEARCHING = new StoredDeclarations.Halves(ASKING.takes(),
            new StoredDeclarations.Half(new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES),
                    DeployedModels.WEB.outputs().collect { field ->
                        new Field(field.name(), null, null, field.shape(), field.howMany(),
                                new Demand.Stands(field.demand().mustBe(), FieldStanding.NEVER, null))
                    }), SEARCH_GIVES.keys()))

    static final StoredWorkflow STORED = new StoredWorkflow(4, TAKES, GIVES, [
            new StoredWorkflow.Step(STEPS[0], new StepId("classify"), null,
                    new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, new EntryVersionId(ASKED)),
                    new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), true), 3,
                    new ModelChoice(new ModelName("small"), null),
                    [new Binding(BINDINGS[0], Pointer.parse("complaint"), new BindingSource.WorkflowInput(Pointer.parse("message")))]),
            new StoredWorkflow.Step(STEPS[1], new StepId("route"), null, new StoredWorkflow.Runs.Route(
                    new Binding(BINDINGS[1], null, new BindingSource.StepOutput(STEPS[0], Pointer.parse("category"))),
                    ROUTE_GIVES,
                    [new RouteCase(CASES[0], "Billing", new EntryVersionId(LED),
                            [new Binding(BINDINGS[2], Pointer.parse("complaint"), new BindingSource.Written(new JsonValue.JsonNumber(new BigDecimal(DIGITS_38))))]),
                     new RouteCase(CASES[1], null, null, [])]), null, null, null, []),
            new StoredWorkflow.Step(STEPS[2], new StepId("send"), null, new StoredWorkflow.Runs.Code("send_reply"),
                    new Producer.Code(), 1, null, []),
            new StoredWorkflow.Step(STEPS[3], new StepId("later"), null, new StoredWorkflow.Runs.Unchosen(), null, null, null, []),
            new StoredWorkflow.Step(STEPS[4], new StepId("find"), null, new StoredWorkflow.Runs.Search("web", SEARCH_ASKED,
                    [(SearchWay.TITLE): FieldStanding.ALWAYS, (SearchWay.BODY): FieldStanding.NEVER]),
                    new Producer.Model(new ModelChoice(new ModelName("general"), null), false), 2, null,
                    [new Binding(PART, Pointer.parse("complaint"), new BindingSource.StepOutput(STEPS[0], Pointer.parse("summary")))])],
            [new Binding(BINDINGS[3], Pointer.parse("summary"), new BindingSource.StepOutput(STEPS[0], Pointer.parse("abstract")))],
            new Ceiling(1000), true, false, true, new ModelChoice(new ModelName("general"), null))

    static final Workflows.WorkflowView READ = new Workflows.WorkflowView(STORED,
            [(new EntryVersionId(ASKED)): ASKING, (new EntryVersionId(LED)): LEADING,
             (new EntryVersionId(LED_NEWER)): NOTHING, (SEARCH_ASKED): SEARCHING],
            [send_reply: SENDING],
            [web: SEARCH_GIVES],
            [(new EntryVersionId(ASKED)): new PinnedVersions.PinnedVersion(new EntryName("Classify"),
                    new EntryVersionId(ASKED), 2, VersionStanding.IN_SERVICE, null, null),
             (new EntryVersionId(LED))  : new PinnedVersions.PinnedVersion(new EntryName("Escalate"),
                     new EntryVersionId(LED), 1, VersionStanding.RETIRED,
                     new RetiredPinsRefusal.NumberedVersion(new EntryVersionId(LED_NEWER), 2, null), null),
             (SEARCH_ASKED): new PinnedVersions.PinnedVersion(new EntryName("Find context"), SEARCH_ASKED, 1,
                     VersionStanding.IN_SERVICE, null, null),
             (new EntryVersionId(LIST)) : new PinnedVersions.PinnedVersion(new EntryName("Categories"),
                     new EntryVersionId(LIST), 1, VersionStanding.IN_SERVICE, null, null)],
            OfferedLists.offering([(new EntryVersionId(LIST)): ["Billing", "Delivery"]]),
            new ContentProblemReport.Result([new ContentProblem(ContentProblemCode.INPUT_UNBOUND,
                    new ContentPlace.AtInput(STEPS[2], null, SENDING.takes().keyAt([0])), null, null),
             new ContentProblem(ContentProblemCode.CASE_TARGET_MISSING, new ContentPlace.AtCase(STEPS[1], CASES[1]), null, null),
             new ContentProblem(ContentProblemCode.CONSTANT_DOES_NOT_FIT,
                     new ContentPlace.AtBinding(ContentPart.STEPS, BINDINGS[2]), null, null),
             new ContentProblem(ContentProblemCode.INPUT_UNBOUND, new ContentPlace.AtInput(STEPS[1], CASES[0], KEYS[7]), null, null),
             new ContentProblem(ContentProblemCode.OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, KEYS[1]), null, null),
             new ContentProblem(ContentProblemCode.SEARCH_STANDING_MISSING,
                     new ContentPlace.AtStepFields(STEPS[4], ["references", "other"]), null, null),
             new ContentProblem(ContentProblemCode.HELPER_NOT_HELD, new ContentPlace.Whole(ContentPart.HELPER), null, null)], 9),
            [new SendPast(STEPS[0], SendingRole.REVIEWING, new ModelName("small"), 1234)],
            new Workflows.Offers(
                    [new Workflows.OfferedCodeStep("send_reply", SENDING)],
                    [new Workflows.OfferedSearchWay(DeployedModels.WEB, SEARCH_GIVES)],
                    DeployedModels.SEARCHING.all()))

    static final List SENDING_TAKES = [[fieldId: SENDING.takes().keyAt([0]).toString(), name: "reply", kind: "text", lines: "several",
                                        longest: 2000, many: false, mustBeGiven: true]]

    static final List SENDING_GIVES = [[fieldId: SENDING.gives().keyAt([0]).toString(), name: "receipt", kind: "text", lines: "several",
                                        longest: 64, many: false, mustBeGiven: true, stands: "always"]]

    static final List WEB_GIVES = [["title", 200, true], ["body", 4000, true], ["references", 2000, false], ["other", 1000, false]]
            .withIndex().collect { output, at ->
                [fieldId: SEARCH_GIVES.keyAt([at]).toString(), name: output[0], kind: "text", lines: "several", longest: output[1], many: false,
                 mustBeGiven: output[2]]
            }

    static final Map COMPLAINT = [fieldId: KEYS[4].toString(), name: "complaint", kind: "text", lines: "several", longest: 4000, many: false,
                                  mustBeGiven: true]

    static final List ASKING_GIVES = [
            [fieldId: KEYS[5].toString(), name: "category", kind: "term", many: false,
             list   : [name: "Categories", versionId: LIST.toString(), number: 1, versionName: null, standing: "in_service"],
             mustBeGiven: false],
            [fieldId: KEYS[6].toString(), name: "summary", kind: "text", lines: "several", longest: 500, many: false, mustBeGiven: false]]

    /** Held whole, so a member added, dropped or renamed on either side of the wire is a change seen here. */
    static final Map ANSWER = [
            revision          : 4,
            takes             : [[fieldId: KEYS[0].toString(), name: "complaint", kind: "text", lines: "several", longest: 4000, many: false,
                                  mustBeGiven: true]],
            gives             : [[fieldId: KEYS[1].toString(), name: "summary", kind: "text", lines: "several", longest: 500, many: false,
                                  mustBeGiven: false]],
            steps             : [
                    [stepId  : STEPS[0].toString(), name: "classify",
                     runs    : [kind : "question",
                                version: [name: "Classify", versionId: ASKED.toString(), number: 2, versionName: null, standing: "in_service"],
                                takes: [COMPLAINT], gives: ASKING_GIVES],
                     producer: [kind: "model", model: "general", mode: "research", toldWhatHappened: true],
                     tries   : 3,
                     reviewer: [model: "small"],
                     bindings: [[bindingId: BINDINGS[0].toString(), target: "complaint", source: [input: "message"]]]],
                    [stepId  : STEPS[1].toString(), name: "route",
                     runs    : [kind         : "route",
                                gives        : [[fieldId: KEYS[2].toString(), name: "note", kind: "text", lines: "several", longest: 100, many: false,
                                                 mustBeGiven: false]],
                                discriminator: [bindingId: BINDINGS[1].toString(), source: [step: 0, path: "category"]],
                                cases        : [
                                        [caseId  : CASES[0].toString(), term: "Billing",
                                         workflow: [name : "Escalate", versionId: LED.toString(), number: 1, versionName: null, standing: "retired",
                                                    newer: [versionId: LED_NEWER.toString(), number: 2, versionName: null]],
                                         takes   : [[fieldId: KEYS[7].toString(), name: "complaint", kind: "text", lines: "several", longest: 4000,
                                                     many   : false, mustBeGiven: true]],
                                         gives   : [],
                                         bindings: [[bindingId: BINDINGS[2].toString(), target: "complaint",
                                                     source   : [constant: DIGITS_38]]]],
                                        [caseId: CASES[1].toString(), bindings: []]]],
                     bindings: []],
                    [stepId: STEPS[2].toString(), name: "send",
                     runs: [kind: "code_step", takes: SENDING_TAKES, gives: SENDING_GIVES, codeStep: "send_reply"],
                     producer: [kind: "code"], tries: 1, bindings: []],
                    [stepId: STEPS[3].toString(), name: "later", bindings: []],
                    [stepId  : STEPS[4].toString(), name: "find",
                     runs    : [kind     : "search", takes: [COMPLAINT], gives: WEB_GIVES.collect { it + [stands: "never"] }, searchWay: "web",
                                version: [name: "Find context", versionId: SEARCH_ASKED.value().toString(), number: 1, versionName: null,
                                          standing: "in_service"],
                                standings: [title: "always", body: "never"]],
                     producer: [kind: "model", model: "general", toldWhatHappened: false],
                     tries   : 2,
                     bindings: [[bindingId: PART.toString(), target: "complaint", source: [step: 0, path: "summary"]]]]],
            outputs           : [[bindingId: BINDINGS[3].toString(), target: "summary", source: [step: 0, path: "abstract"]]],
            ceiling           : "1000",
            keepsOwnCeiling   : true,
            raiseNeedsApproval: false,
            mayBeHelped       : true,
            helper            : [model: "general"],
            problemTotal      : 9,
            problems          : [
                    [code: "input_unbound", part: "steps", stepId: STEPS[2].toString(),
                     fieldId: SENDING.takes().keyAt([0]).toString()],
                    [code: "case_target_missing", part: "steps", stepId: STEPS[1].toString(), caseId: CASES[1].toString()],
                    [code: "constant_does_not_fit", part: "steps", bindingId: BINDINGS[2].toString()],
                    [code: "input_unbound", part: "steps", stepId: STEPS[1].toString(), caseId: CASES[0].toString(),
                     fieldId: KEYS[7].toString()],
                    [code: "output_unbound", part: "gives", fieldId: KEYS[1].toString()],
                    [code: "search_standing_missing", part: "steps", stepId: STEPS[4].toString(), fields: ["references", "other"]],
                    [code: "helper_not_held", part: "helper"]],
            sendsPast         : [[stepId: STEPS[0].toString(), role: "reviewing", model: "small", past: 1234]],
            terms             : [(LIST.toString()): ["Billing", "Delivery"]],
            offered           : [
                    codeSteps: [[name: "send_reply", takes: SENDING_TAKES, gives: SENDING_GIVES]],
                    searchWays: [[name: "web", tool: "web_search", gives: WEB_GIVES]],
                    models   : [[name: "general", modes: ["research", "deep_research"], tools: ["web_search", "archive_search"]],
                                [name: "small", modes: [], tools: []]]]]

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private Workflows workflows

    @MockitoBean
    private WorkflowDrafts drafts

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
        given(workflows.read(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER)).willReturn(READ)
        given(drafts.declare(any(), any(), any(), any(), anyInt(), any(), any())).willReturn(READ)
        given(drafts.flow(any(), any(), any(), any(), any())).willReturn(READ)
        given(drafts.ceiling(any(), any(), any(), any(), anyInt(), any(), anyBoolean(), anyBoolean())).willReturn(READ)
        given(drafts.help(any(), any(), any(), any(), anyInt(), anyBoolean(), any())).willReturn(READ)
    }

    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private static MockHttpServletRequestBuilder sent(String address, String body) {
        put(address).contentType(MediaType.APPLICATION_JSON).content(body)
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    /** Use void so Groovy does not interpret the helper result as an assertion. */
    private void writtenAlone(Closure<?> change) {
        change(Mockito.verify(drafts))
        Mockito.verifyNoMoreInteractions(drafts)
        Mockito.verifyNoInteractions(workflows)
    }


    def "reads a workflow version to any member, as the library reads it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(get(AT_VERSION))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        Mockito.verifyNoInteractions(drafts)
    }

    def "a workflow response carries display metadata beside its machine name and omits an absent display name"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def first = STORED.steps()[0]
        def changed = new StoredWorkflow.Step(first.id(), first.name(),
                label == null ? null : new com.lilradish.lite.domain.workflow.StepLabel(label), first.runs(),
                first.producer(), first.tries(), first.reviewer(), first.bindings())
        def stored = new StoredWorkflow(STORED.revision(), STORED.takes(), STORED.gives(),
                [changed] + STORED.steps().drop(1), STORED.outputs(), STORED.ceiling(), STORED.keepsOwnCeiling(),
                STORED.raiseNeedsApproval(), STORED.mayBeHelped(), STORED.helper())
        def view = new Workflows.WorkflowView(stored, READ.declared(), READ.codeSteps(), READ.searchGives(),
                READ.pins(), READ.terms(), READ.problems(), READ.sendsPast(), READ.offers())
        given(workflows.read(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER)).willReturn(view)

        when:
        def answered = sending(get(AT_VERSION))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER + [steps:
                [ANSWER.steps[0] + expected] + ANSWER.steps.drop(1)]
        Mockito.verifyNoInteractions(drafts)

        where:
        label       || expected
        null        || [:]
        "申請を分類\u202e" || [label: "申請を分類\u202e"]
    }

    def "pinned question and workflow responses carry their current names, including explicit unnamed values"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def name = currentName == null ? null : new com.lilradish.lite.domain.registry.VersionName(currentName)
        def pins = READ.pins().collectEntries { id, pinned ->
            [id, new PinnedVersions.PinnedVersion(pinned.name(), pinned.version(), pinned.number(),
                    pinned.standing(), pinned.newer(), name)]
        }
        def view = new Workflows.WorkflowView(READ.stored(), READ.declared(), READ.codeSteps(), READ.searchGives(),
                pins, READ.terms(), READ.problems(), READ.sendsPast(), READ.offers())
        given(workflows.read(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER)).willReturn(view)

        when:
        def answered = sending(get(AT_VERSION))

        then:
        answered.response.status == 200
        def document = JSON.convertValue(documentOf(answered), Map)
        document.steps[0].runs.version.versionName == currentName
        document.steps[1].runs.cases[0].workflow.versionName == currentName
        document.offered == ANSWER.offered
        Mockito.verifyNoInteractions(drafts)

        where:
        currentName << [null, "مرحلة 📦", "Renamed current version"]
    }

    def "the dispatcher accepts an optional display name while sending the original machine binding body"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def body = [revision: 2, steps: [[name: "later", label: label, runs: null, bindings: []]],
                    outputs: [[target: "summary", source: [step: 0, path: "summary"]]]]

        when:
        def answered = sending(sent(AT_VERSION + "/steps", JSON.writeValueAsString(body)))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER
        writtenAlone {
            it.flow(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER,
                    new FlowBody.Sent(2, [new FlowBody.SentStep(null, new StepId("later"), expected,
                            new FlowBody.SentRuns.Unchosen(), null, null, null, [])],
                            [new FlowBody.SentBinding(Pointer.parse("summary"),
                                    new FlowBody.SentSource.Step(0, Pointer.parse("summary")))]))
        }

        where:
        label        || expected
        null         || null
        ""           || null
        "後で確認\u202e" || new com.lilradish.lite.domain.workflow.StepLabel("後で確認\u202e")
    }

    def "writes the half the address names, read as a workflow's halves ask, and answers with the version as the change read it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/" + side.published(), '{"revision":3,"fields":[' + field + ']}'))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        writtenAlone {
            it.declare(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 3,
                    new Declaration(side, Demands.ofWorkflow(side), [declared]), [null])
        }

        where:
        side                  | field                                                                                                                  || declared
        DeclarationSide.TAKES | '{"name":"urgent","label":null,"help":null,"kind":"text","many":false,"most":null,"longest":10,"mustBeGiven":false}' || new Field(new FieldName("urgent"), null, null, new FieldShape.Text(10), new HowMany.One(), new Demand.Given(false))
        DeclarationSide.GIVES | '{"name":"summary","label":null,"help":null,"kind":"text","many":false,"most":null,"longest":500,"mustBeGiven":true}'  || new Field(new FieldName("summary"), null, null, new FieldShape.Text(500), new HowMany.One(), new Demand.Given(true))
    }

    def "writes a draft file field with sixty-four types and the exact largest byte count"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def types = (0..<64).collect { ".x${it}" as String }
        def field = [name: "attachments", label: null, help: null, kind: "file", many: false, most: null,
                     types: types, maximumBytes: 9_007_199_254_740_991L, mustBeGiven: false]

        when:
        def answered = sending(sent(AT_VERSION + "/gives", JSON.writeValueAsString([revision: 3, fields: [field]])))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER
        writtenAlone {
            it.declare(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 3,
                    new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES),
                            [held("attachments", new FieldShape.File(types.collect { new FileExtension(it) },
                                    9_007_199_254_740_991L))]), [null])
        }
    }

    def "refuses an invalid file limit while changing a draft, without asking the draft writer"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def field = [name: "attachments", label: null, help: null, kind: "file", many: false, most: null,
                     types: types, maximumBytes: bytes, mustBeGiven: false]

        when:
        def answered = sending(sent(AT_VERSION + "/gives", JSON.writeValueAsString([revision: 3, fields: [field]])))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        types                                                    | bytes                 || code
        FILE_TYPES_65                                            | 1L                    || "FILE_TYPES_TOO_MANY"
        [".pdf"]                                      | 9_007_199_254_740_992L || "FILE_SIZE_LIMIT_UNUSABLE"
        [".pdf"]                                      | 1.5G                  || "FILE_SIZE_LIMIT_UNUSABLE"
        [".pdf"]                                      | 0L                    || "FILE_SIZE_LIMIT_UNUSABLE"
        [".pdf"]                                      | -1L                   || "FILE_SIZE_LIMIT_UNUSABLE"
    }


    def "a field a workflow gives back asking to stand is refused as a body its depth does not take"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/gives",
                '{"revision":3,"fields":[{"name":"summary","label":null,"help":null,"kind":"text","many":false,"most":null,"longest":500,"stands":"always","floor":null}]}'))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        Mockito.verifyNoInteractions(drafts, workflows)
    }

    def "writes every step and what fills each value given back, as the body sends them"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def body = '{"revision":2,"steps":[{"name":"later","runs":null,"bindings":[]}],' +
                '"outputs":[{"target":"summary","source":{"step":0,"path":"summary"}},' +
                '{"target":"amount","source":{"constant":"' + DIGITS_38 + '"}}]}'

        when:
        def answered = sending(sent(AT_VERSION + "/steps", body))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        writtenAlone {
            it.flow(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, new FlowBody.Sent(2,
                    [new FlowBody.SentStep(null, new StepId("later"), null, new FlowBody.SentRuns.Unchosen(), null, null, null, [])],
                    [new FlowBody.SentBinding(Pointer.parse("summary"), new FlowBody.SentSource.Step(0, Pointer.parse("summary"))),
                     new FlowBody.SentBinding(Pointer.parse("amount"), new FlowBody.SentSource.Written(DIGITS_38))]))
        }
    }

    /** Decimal exponents at the integer bound must be refused before expanding their digits. */
    def "a constant at the edge of what a decimal holds is refused as one that does not fit, before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/steps",
                '{"revision":2,"steps":[],"outputs":[{"target":"summary","source":{"constant":"' + constant + '"}}]}'))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "CONSTANT_DOES_NOT_FIT"
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        constant << ["1e2147483647", "-1e2147483647", "12e2147483647", "1e-2147483647"]
    }

    def "a searching step with a retired literal question is refused before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def body = '{"revision":2,"steps":[{"name":"find_context","runs":{"kind":"search","searchWay":"web_search",' +
                '"question":[{"text":"Imaginary old question"}],"standings":{"title":null,"body":null,"references":null,"other":null}},' +
                '"producer":null,"tries":null,"reviewer":null,"bindings":[]}],"outputs":[]}'

        when:
        def answered = sending(sent(AT_VERSION + "/steps", body))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        Mockito.verifyNoInteractions(drafts, workflows)
    }


    def "sets the ceiling as digits or none, and the two declarations beside it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/ceiling",
                '{"revision":5,"ceiling":' + typed + ',"keepsOwnCeiling":true,"raiseNeedsApproval":false}'))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        writtenAlone {
            it.ceiling(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 5, ceiling, true, false)
        }

        where:
        typed                 || ceiling
        '"9007199254740991"'  || new Ceiling(Ceiling.LARGEST)
        'null'                || null
    }

    def "a ceiling that is no whole number from one up to the largest is refused, before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/ceiling",
                '{"revision":5,"ceiling":' + typed + ',"keepsOwnCeiling":true,"raiseNeedsApproval":false}'))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        typed                  || code
        '"0"'                  || "CEILING_UNUSABLE"
        '"9007199254740992"'   || "CEILING_UNUSABLE"
        '"01"'                 || "CEILING_UNUSABLE"
        '"1e3"'                || "CEILING_UNUSABLE"
        '1000'                 || "BODY_UNUSABLE"
    }

    def "sets whether runs may be helped, and by which model and mode"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/help", '{"revision":6,"mayBeHelped":' + may + ',"helper":' + helper + '}'))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        writtenAlone {
            it.help(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 6, may, chosen)
        }

        where:
        may   | helper                                    || chosen
        true  | '{"model":"general","mode":"research"}'   || new ModelChoice(new ModelName("general"), new ModelMode("research"))
        true  | '{"model":"general","mode":null}'         || new ModelChoice(new ModelName("general"), null)
        true  | 'null'                                    || null
        false | 'null'                                    || null
    }


    def "a helper named where it may not be, or named as no model could be, is refused before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/help", body))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        body << ['{"revision":6,"mayBeHelped":false,"helper":{"model":"general","mode":null}}',
                 '{"revision":6,"mayBeHelped":true,"helper":{"model":"General","mode":null}}',
                 '{"revision":6,"mayBeHelped":true,"helper":{"model":"general","mode":"ordinary"}}',
                 '{"revision":6,"mayBeHelped":true,"helper":{"model":"general"}}',
                 '{"revision":6,"mayBeHelped":"yes","helper":null}',
                 '{"revision":6,"mayBeHelped":true}']
    }


    def "a write naming no revision it read, or one that is none, is refused before the store is asked"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(sent(AT_VERSION + suffix, body))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        suffix     | body
        "/takes"   | '{"fields":[]}'
        "/takes"   | '{"revision":0,"fields":[]}'
        "/takes"   | '[]'
        "/steps"   | '{"revision":"1","steps":[],"outputs":[]}'
        "/ceiling" | '{"ceiling":null,"keepsOwnCeiling":true,"raiseNeedsApproval":false,"x":1}'
        "/ceiling" | '{"revision":-2,"ceiling":null,"keepsOwnCeiling":true,"raiseNeedsApproval":false}'
        "/help"    | '{"revision":1.5,"mayBeHelped":false,"helper":null}'
    }


    def "a search shape refusal carries only admitted question facts in its actual HTTP problem: #named"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        def refusal = new SearchPinRefusal("web", named ? new EntryName("Fictional finder") : null, named ? 3 : null)
        willThrow(refusal).given(drafts).flow(
                new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, new FlowBody.Sent(1, [], []))

        when:
        def answered = sending(sent(AT_VERSION + "/steps", '{"revision":1,"steps":[],"outputs":[]}'))

        then:
        answered.response.status == 400
        def document = documentOf(answered)
        document.get("code").asString() == "SEARCH_QUESTION_DOES_NOT_MATCH"
        document.get("searchWay").asString() == "web"
        document.has("questionName") == named
        document.has("questionNumber") == named
        !document.has("questionId")
        !document.has("versionId")
        !document.get("detail").asString().contains(ASKED.toString())
        if (named) {
            assert document.get("questionName").asString() == "Fictional finder"
            assert document.get("questionNumber").asInt() == 3
            assert document.get("detail").asString() == "Fictional finder · Version 3 can't be used to search with web: its answer isn't exactly what web gives back. Pick a version set for searching with web."
        } else {
            assert document.get("detail").asString() == "That question can't be used to search with web: its answer isn't exactly what web gives back. Pick a version set for searching with web."
        }
        Mockito.verifyNoInteractions(workflows)

        where:
        named << [true, false]
    }

    def "answers a write the store refuses with that refusal, and reads nothing after it"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        willThrow(LibraryRefusal.VERSION_STANDING_REFUSES.raised()).given(drafts).flow(
                new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, new FlowBody.Sent(1, [], []))

        when:
        def answered = sending(sent(AT_VERSION + "/steps", '{"revision":1,"steps":[],"outputs":[]}'))

        then:
        answered.response.status == 409
        documentOf(answered).get("code").asString() == "VERSION_STANDING_REFUSES"
        Mockito.verifyNoInteractions(workflows)
    }


    def "refuses a read or a write by a caller in no role here as no group, never asking the store"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "GROUP_NOT_IN_VIEW"
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        request << [get(AT_VERSION), sent(AT_VERSION + "/steps", '{"revision":1,"steps":[],"outputs":[]}'),
                    sent(AT_VERSION + "/help", '{"revision":1,"mayBeHelped":false,"helper":null}')]
    }

    def "refuses an address naming no version alike whichever identifier names nothing, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "VERSION_NOT_IN_VIEW"
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        request << [get("/api/groups/${GROUP}/workflows/not-an-entry/versions/${VERSION}"),
                    get("/api/groups/${GROUP}/workflows/${ENTRY}/versions/not-a-version"),
                    sent("/api/groups/${GROUP}/workflows/${ENTRY}/versions/not-a-version/ceiling",
                            '{"revision":1,"ceiling":null,"keepsOwnCeiling":false,"raiseNeedsApproval":false}')]
    }

    def "refuses a parameter no address of a version takes, never asking the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "PARAMETER_UNKNOWN"
        Mockito.verifyNoInteractions(drafts, workflows)

        where:
        request << [get(AT_VERSION).queryParam("side", "takes"),
                    sent(AT_VERSION + "/steps", '{"revision":1,"steps":[],"outputs":[]}').queryParam("x", "1"),
                    sent(AT_VERSION + "/gives", '{"revision":1,"fields":[]}').queryParam("x", "1"),
                    sent(AT_VERSION + "/ceiling", '{"revision":1,"ceiling":null,"keepsOwnCeiling":true,"raiseNeedsApproval":false}')
                            .queryParam("x", "1"),
                    sent(AT_VERSION + "/help", '{"revision":1,"mayBeHelped":false,"helper":null}').queryParam("x", "1")]
    }


    def "a workflow version and its halves are addressed under the spellings their vocabularies publish"() {
        expect:
        WorkflowController.VERSION.contains("/" + EntryKind.WORKFLOW.segment() + "/")
        (WorkflowController.HALF =~ /\{side:([^}]*)}/)[0][1].split(/\|/) as List == DeclarationSide.values()*.published()
    }

    private static StoredDeclarations.Half half(DeclarationSide side, List<Field> fields, List<UUID> keys) {
        new StoredDeclarations.Half(new Declaration(side, Demands.ofWorkflow(side), fields),
                keys.collect { new StoredDeclarations.Keyed(it, []) })
    }

    private static Field taken(String name, int longest) {
        new Field(new FieldName(name), null, null, new FieldShape.Text(longest), new HowMany.One(), new Demand.Given(true))
    }

    private static Field held(String name, FieldShape shape) {
        new Field(new FieldName(name), null, null, shape, new HowMany.One(), new Demand.Given(false))
    }
}
