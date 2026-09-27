package org.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyBoolean
import static org.mockito.ArgumentMatchers.anyInt
import static org.mockito.BDDMockito.given
import static org.mockito.BDDMockito.willThrow
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.domain.run.Ceiling
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.RouteCase
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.OfferedLists
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
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

/**
 * What reading a workflow version and writing each part of it are answered with, over a real dispatcher: what
 * each request was turned into, the document it answers with, and every refusal met before the store is asked.
 * The store is replaced, and so is who is calling and what they hold.
 */
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

    static final List<UUID> STEPS = (1..4).collect { UUID.fromString("0000000c-0000-4000-8000-000000000f3${it}") }

    static final List<UUID> CASES = (1..2).collect { UUID.fromString("0000000d-0000-4000-8000-000000000f3${it}") }

    static final List<UUID> BINDINGS = (1..4).collect { UUID.fromString("0000000e-0000-4000-8000-000000000f3${it}") }

    static final String AT_VERSION = "/api/groups/${GROUP}/workflows/${ENTRY}/versions/${VERSION}"

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

    /** What the release declares of the code step offered, keyed as a reader is answered it. */
    static final StoredDeclarations.Halves SENDING = StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())

    static final StoredWorkflow STORED = new StoredWorkflow(4, TAKES, GIVES, [
            new StoredWorkflow.Step(STEPS[0], new StepId("classify"),
                    new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, new EntryVersionId(ASKED)),
                    new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), true), 3,
                    new ModelChoice(new ModelName("small"), null),
                    [new Binding(BINDINGS[0], Pointer.parse("complaint"), new BindingSource.WorkflowInput(Pointer.parse("message")))]),
            new StoredWorkflow.Step(STEPS[1], new StepId("route"), new StoredWorkflow.Runs.Route(
                    new Binding(BINDINGS[1], null, new BindingSource.StepOutput(STEPS[0], Pointer.parse("category"))),
                    ROUTE_GIVES,
                    [new RouteCase(CASES[0], "Billing", new EntryVersionId(LED),
                            [new Binding(BINDINGS[2], Pointer.parse("complaint"), new BindingSource.Written(new JsonValue.JsonNumber(new BigDecimal(DIGITS_38))))]),
                     new RouteCase(CASES[1], null, null, [])]), null, null, null, []),
            new StoredWorkflow.Step(STEPS[2], new StepId("send"), new StoredWorkflow.Runs.Code("send_reply"),
                    new Producer.Code(), 1, null, []),
            new StoredWorkflow.Step(STEPS[3], new StepId("later"), new StoredWorkflow.Runs.Unchosen(), null, null, null, [])],
            [new Binding(BINDINGS[3], Pointer.parse("summary"), new BindingSource.StepOutput(STEPS[0], Pointer.parse("abstract")))],
            new Ceiling(1000), true, false, true, new ModelChoice(new ModelName("general"), null))

    static final Workflows.WorkflowView READ = new Workflows.WorkflowView(STORED,
            [(new EntryVersionId(ASKED)): ASKING, (new EntryVersionId(LED)): LEADING, (new EntryVersionId(LED_NEWER)): NOTHING],
            [send_reply: SENDING],
            [(new EntryVersionId(ASKED)): new PinnedVersions.PinnedVersion(new EntryName("Classify"),
                    new EntryVersionId(ASKED), 2, VersionStanding.IN_SERVICE, null),
             (new EntryVersionId(LED))  : new PinnedVersions.PinnedVersion(new EntryName("Escalate"),
                     new EntryVersionId(LED), 1, VersionStanding.RETIRED,
                     new RetiredPinsRefusal.NumberedVersion(new EntryVersionId(LED_NEWER), 2)),
             (new EntryVersionId(LIST)) : new PinnedVersions.PinnedVersion(new EntryName("Categories"),
                     new EntryVersionId(LIST), 1, VersionStanding.IN_SERVICE, null)],
            OfferedLists.offering([(new EntryVersionId(LIST)): ["Billing", "Delivery"]]),
            [new ContentProblem(ContentProblemCode.INPUT_UNBOUND,
                    new ContentPlace.AtInput(STEPS[2], null, SENDING.takes().keyAt([0])), null),
             new ContentProblem(ContentProblemCode.CASE_TARGET_MISSING, new ContentPlace.AtCase(STEPS[1], CASES[1]), null),
             new ContentProblem(ContentProblemCode.CONSTANT_DOES_NOT_FIT,
                     new ContentPlace.AtBinding(ContentPart.STEPS, BINDINGS[2]), null),
             new ContentProblem(ContentProblemCode.INPUT_UNBOUND, new ContentPlace.AtInput(STEPS[1], CASES[0], KEYS[7]), null),
             new ContentProblem(ContentProblemCode.OUTPUT_UNBOUND, new ContentPlace.AtField(ContentPart.GIVES, KEYS[1]), null),
             new ContentProblem(ContentProblemCode.HELPER_NOT_HELD, new ContentPlace.Whole(ContentPart.HELPER), null)],
            [new SendPast(STEPS[0], SendingRole.REVIEWING, new ModelName("small"), 1234)],
            new Workflows.Offers(
                    [new PinnedVersions.OfferedVersion(new EntryName("Categories"), new EntryVersionId(LIST), 1)],
                    [new Workflows.Offered(new PinnedVersions.OfferedVersion(new EntryName("Classify"), new EntryVersionId(ASKED), 2), ASKING)],
                    [new Workflows.Offered(new PinnedVersions.OfferedVersion(new EntryName("Escalate"), new EntryVersionId(LED_NEWER), 2), NOTHING)],
                    [new Workflows.OfferedCodeStep("send_reply", SENDING)],
                    DeployedModels.HELD.all()))

    static final List SENDING_TAKES = [[fieldId: SENDING.takes().keyAt([0]).toString(), name: "reply", kind: "text",
                                        longest: 2000, many: false, mustBeGiven: true]]

    static final List SENDING_GIVES = [[fieldId: SENDING.gives().keyAt([0]).toString(), name: "receipt", kind: "text",
                                        longest: 64, many: false, mustBeGiven: true, stands: "always"]]

    static final Map COMPLAINT = [fieldId: KEYS[4].toString(), name: "complaint", kind: "text", longest: 4000, many: false,
                                  mustBeGiven: true]

    static final List ASKING_GIVES = [
            [fieldId: KEYS[5].toString(), name: "category", kind: "term", many: false,
             list   : [name: "Categories", versionId: LIST.toString(), number: 1, standing: "in_service"],
             mustBeGiven: false],
            [fieldId: KEYS[6].toString(), name: "summary", kind: "text", longest: 500, many: false, mustBeGiven: false]]

    /** Held whole, so a member added, dropped or renamed on either side of the wire is a change seen here. */
    static final Map ANSWER = [
            revision          : 4,
            takes             : [[fieldId: KEYS[0].toString(), name: "complaint", kind: "text", longest: 4000, many: false,
                                  mustBeGiven: true]],
            gives             : [[fieldId: KEYS[1].toString(), name: "summary", kind: "text", longest: 500, many: false,
                                  mustBeGiven: false]],
            steps             : [
                    [stepId  : STEPS[0].toString(), name: "classify",
                     runs    : [kind : "question",
                                version: [name: "Classify", versionId: ASKED.toString(), number: 2, standing: "in_service"],
                                takes: [COMPLAINT], gives: ASKING_GIVES],
                     producer: [kind: "model", model: "general", mode: "research", toldWhatHappened: true],
                     tries   : 3,
                     reviewer: [model: "small"],
                     bindings: [[bindingId: BINDINGS[0].toString(), target: "complaint", source: [input: "message"]]]],
                    [stepId  : STEPS[1].toString(), name: "route",
                     runs    : [kind         : "route",
                                gives        : [[fieldId: KEYS[2].toString(), name: "note", kind: "text", longest: 100, many: false,
                                                 mustBeGiven: false]],
                                discriminator: [bindingId: BINDINGS[1].toString(), source: [step: 0, path: "category"]],
                                cases        : [
                                        [caseId  : CASES[0].toString(), term: "Billing",
                                         workflow: [name : "Escalate", versionId: LED.toString(), number: 1, standing: "retired",
                                                    newer: [versionId: LED_NEWER.toString(), number: 2]],
                                         takes   : [[fieldId: KEYS[7].toString(), name: "complaint", kind: "text", longest: 4000,
                                                     many   : false, mustBeGiven: true]],
                                         gives   : [],
                                         bindings: [[bindingId: BINDINGS[2].toString(), target: "complaint",
                                                     source   : [constant: DIGITS_38]]]],
                                        [caseId: CASES[1].toString(), bindings: []]]],
                     bindings: []],
                    [stepId: STEPS[2].toString(), name: "send",
                     runs: [kind: "code_step", takes: SENDING_TAKES, gives: SENDING_GIVES, codeStep: "send_reply"],
                     producer: [kind: "code"], tries: 1, bindings: []],
                    [stepId: STEPS[3].toString(), name: "later", bindings: []]],
            outputs           : [[bindingId: BINDINGS[3].toString(), target: "summary", source: [step: 0, path: "abstract"]]],
            ceiling           : "1000",
            keepsOwnCeiling   : true,
            raiseNeedsApproval: false,
            mayBeHelped       : true,
            helper            : [model: "general"],
            problems          : [
                    [code: "input_unbound", part: "steps", stepId: STEPS[2].toString(),
                     fieldId: SENDING.takes().keyAt([0]).toString()],
                    [code: "case_target_missing", part: "steps", stepId: STEPS[1].toString(), caseId: CASES[1].toString()],
                    [code: "constant_does_not_fit", part: "steps", bindingId: BINDINGS[2].toString()],
                    [code: "input_unbound", part: "steps", stepId: STEPS[1].toString(), caseId: CASES[0].toString(),
                     fieldId: KEYS[7].toString()],
                    [code: "output_unbound", part: "gives", fieldId: KEYS[1].toString()],
                    [code: "helper_not_held", part: "helper"]],
            sendsPast         : [[stepId: STEPS[0].toString(), role: "reviewing", model: "small", past: 1234]],
            terms             : [(LIST.toString()): ["Billing", "Delivery"]],
            offered           : [
                    lists    : [[name: "Categories", versionId: LIST.toString(), number: 1]],
                    questions: [[name: "Classify", versionId: ASKED.toString(), number: 2, takes: [COMPLAINT], gives: ASKING_GIVES]],
                    workflows: [[name: "Escalate", versionId: LED_NEWER.toString(), number: 2, takes: [], gives: []]],
                    codeSteps: [[name: "send_reply", takes: SENDING_TAKES, gives: SENDING_GIVES]],
                    models   : [[name: "general", modes: ["research"]], [name: "small", modes: []]]]]

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

    /**
     * Void, so the verification is what fails rather than the null a mock's answer would assert as. The answer is
     * what the change itself read, so nothing reads the version again beside it.
     */
    private void writtenAlone(Closure<?> change) {
        change(Mockito.verify(drafts))
        Mockito.verifyNoMoreInteractions(drafts)
        Mockito.verifyNoInteractions(workflows)
    }

    /** Each key beside what it keys, each version pinned named, and every member nothing was chosen for left out. */
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

    /** What a workflow gives back stood where it was made, so a field of it saying how it stands is refused whole. */
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
                    [new FlowBody.SentStep(null, new StepId("later"), new FlowBody.SentRuns.Unchosen(), null, null, null, [])],
                    [new FlowBody.SentBinding(Pointer.parse("summary"), new FlowBody.SentSource.Step(0, Pointer.parse("summary"))),
                     new FlowBody.SentBinding(Pointer.parse("amount"), new FlowBody.SentSource.Written(DIGITS_38))]))
        }
    }

    /** An exponent at the int bound once passed as a small number, then filled memory writing its digits out. */
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

    /** A helper is named only for runs that may be helped, and only as a model and a mode a deployment could name. */
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

    /** Every write names the revision it read, a whole number from one, and one that does not is refused whole. */
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

    /** What the store refused is answered as the store refused it; the version is not read again. */
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

    /** Whatever the caller holds in the estate, holding nothing here reaches no version of the group's. */
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

    /** The workflow kind's own segment, and the halves each under the spelling they are published by. */
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
