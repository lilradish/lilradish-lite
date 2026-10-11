package com.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyInt
import static org.mockito.BDDMockito.given
import static org.mockito.BDDMockito.willThrow
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldHelp
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldLabel
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.Instruction
import com.lilradish.lite.domain.declaration.OfferedTerms
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.Envelope
import com.lilradish.lite.domain.referencelist.ListNote
import com.lilradish.lite.domain.referencelist.Term
import com.lilradish.lite.domain.referencelist.TermMeaning
import com.lilradish.lite.domain.registry.EntryId
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.EntryName
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.registry.VersionStanding
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

/**
 * What reading a question version and writing its instruction and either half are answered with, over a
 * real dispatcher: what each request was turned into, the document it answers with, and every refusal met
 * before the store is asked. The store is replaced, and so is who is calling and what they hold.
 */
@WebMvcTest(QuestionController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class QuestionControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000f21")

    static final UUID ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000f21")

    static final UUID VERSION = UUID.fromString("00000007-0000-4000-8000-000000000f21")

    static final UUID LIST = UUID.fromString("00000007-0000-4000-8000-000000000f22")

    static final UUID NEWER = UUID.fromString("00000007-0000-4000-8000-000000000f23")

    static final List<UUID> KEYS = (1..5).collect { UUID.fromString("0000000b-0000-4000-8000-000000000f2${it}") }

    static final String AT_VERSION = "/api/groups/${GROUP}/questions/${ENTRY}/versions/${VERSION}"

    static final OfferedTerms OFFERED = new OfferedTerms(
            [new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge."))], new ListNote("Choose one."))

    static final Questions.QuestionView READ = new Questions.QuestionView(3,
            new StoredQuestion(new Instruction("Say which category."), "## {{complaint}}", null,
                    new StoredDeclarations.Half(new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), [
                            new Field(new FieldName("complaint"), new FieldLabel("The complaint"),
                                    new FieldHelp("As written."), new FieldShape.Text(4000), new HowMany.One(),
                                    new Demand.Given(true))]),
                            [new StoredDeclarations.Keyed(KEYS[0], [])]),
                    new StoredDeclarations.Half(new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), [
                            new Field(new FieldName("category"), null, null,
                                    new FieldShape.Term(new EntryVersionId(LIST)), new HowMany.Many(2),
                                    new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, 80)),
                            new Field(new FieldName("details"), null, null, new FieldShape.Nested([
                                    new Field(new FieldName("received"), null, null,
                                            new FieldShape.Plain(FieldKind.MOMENT), new HowMany.Many(null),
                                            new Demand.Given(false))]),
                                    new HowMany.One(), new Demand.Stands(false, null, null))]),
                            [new StoredDeclarations.Keyed(KEYS[1], []),
                             new StoredDeclarations.Keyed(KEYS[2], [new StoredDeclarations.Keyed(KEYS[3], [])])])),
            [(new EntryVersionId(LIST)): new PinnedVersions.PinnedVersion(new EntryName("Categories"),
                    new EntryVersionId(LIST), 1, VersionStanding.IN_SERVICE,
                    new RetiredPinsRefusal.NumberedVersion(new EntryVersionId(NEWER), 2, null), null)],
            [new AskedField(new FieldName("category"), FieldKind.TERM, null, 2, OFFERED, [], true, true),
             new AskedField(new FieldName("details"), FieldKind.FIELDS, null, null, null, [
                     new AskedField(new FieldName("sku"), FieldKind.TEXT, 32, null, null, [], true, false)],
                     false, false)], [], false)

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private Questions questions

    @MockitoBean
    private QuestionDrafts drafts

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
        given(questions.read(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER))
                .willReturn(READ)
        given(drafts.instruct(any(), any(), any(), any(), anyInt(), any())).willReturn(READ)
        given(drafts.draw(any(), any(), any(), any(), anyInt(), any())).willReturn(READ)
        given(drafts.declare(any(), any(), any(), any(), anyInt(), any(), any())).willReturn(READ)
        given(drafts.searching(any(), any(), any(), any(), anyInt(), any())).willReturn(READ)
    }

    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private static MockHttpServletRequestBuilder sent(String address, String body) {
        put(address).contentType(MediaType.APPLICATION_JSON).content(body)
    }

    /** Written as JSON escapes, so what reaches the reader is exactly the UTF-16 units named. */
    private static String escaping(String before, List<Integer> units, String after) {
        '{"revision":1,"instruction":"' + before + units.collect { String.format('\\u%04X', it) }.join() + after + '"}'
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
        Mockito.verifyNoInteractions(questions)
    }

    /** Held whole, so a member added, dropped or renamed on either side of the wire is a change seen here. */
    static final Map ANSWER = [
            revision   : 3,
            searchShapeNeedsRefresh: false,
            searchWays : [],
            instruction: "Say which category.",
            drawing    : "## {{complaint}}",
            takes      : [[fieldId: KEYS[0].toString(), name: "complaint", label: "The complaint", help: "As written.",
                           kind   : "text", lines: "several", longest: 4000, many: false, mustBeGiven: true]],
            gives      : [[fieldId: KEYS[1].toString(), name: "category", kind: "term",
                           list   : [name: "Categories", versionId: LIST.toString(),
                                     number : 1, versionName: null, standing: "in_service", newer: [versionId: NEWER.toString(), number: 2, versionName: null]],
                           many   : true, most: 2, mustBeGiven: true, stands: "above_confidence", floor: 80],
                          [fieldId: KEYS[2].toString(), name: "details", kind: "fields", many: false, mustBeGiven: false,
                           fields : [[fieldId: KEYS[3].toString(), name: "received", kind: "moment", many: true,
                                      mustBeGiven: false]]]],
            added      : Envelope.toProduce(READ.added(), READ.stored().instruction()),
            ]

    /** Each field's key beside it, each list it pins named, and every member nothing was chosen for left out. */
    def "reads a question version to any member, as the library reads it"() {
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

    def "writes the instruction as sent, saying nothing where it is null, and answers with the version as the change read it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/instruction", body))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and: "the change made at the revision the page read, and no read beside it"
        writtenAlone {
            it.instruct(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, seen, said)
        }

        where:
        body                                                        || seen       | said
        '{"revision":3,"instruction":"Say why.\\n\\tBriefly."}'     || 3          | new Instruction("Say why.\n\tBriefly.")
        '{"instruction":null,"revision":1}'                         || 1          | null
        '{"revision":2147483647,"instruction":null}'                || 2147483647 | null
    }

    def "writes a search marker exactly once as the dispatcher read its string or null"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        def base = '{"revision":' + seen + ',"searchWay":' + value + '}'
        def body = base + (' ' * padding)

        when:
        def answered = sending(sent(AT_VERSION + '/searching', body))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER
        writtenAlone {
            it.searching(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, seen, expected)
        }

        where:
        value  | seen       | padding                 || expected
        '"web"' | 1          | 0                       || 'web'
        'null'  | 1          | 0                       || null
        'null'  | 2147483647 | 0                       || null
        '"web"' | 1          | 1024 - '{"revision":1,"searchWay":"web"}'.length() || 'web'
    }

    def "refuses malformed search marker bodies and revisions before any draft or read interaction"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(sent(AT_VERSION + '/searching', body))

        then:
        answered.response.status == 400
        documentOf(answered).get('code').asString() == 'BODY_UNUSABLE'
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        body << [
                '[]', 'null', '"web"', '{}',
                '{"revision":1}', '{"searchWay":"web"}',
                '{"revision":1,"searchWay":"web","extra":null}',
                '{"revision":1,"searchWay":3}', '{"revision":1,"searchWay":false}',
                '{"revision":1,"searchWay":[]}', '{"revision":1,"searchWay":{}}',
                '{"revision":1,"searchWay":""}', '{"revision":1,"searchWay":"Web"}',
                '{"revision":1,"searchWay":"two words"}',
                '{"revision":0,"searchWay":null}', '{"revision":-1,"searchWay":null}',
                '{"revision":1.0,"searchWay":null}', '{"revision":1.5,"searchWay":null}',
                '{"revision":"1","searchWay":null}', '{"revision":null,"searchWay":null}',
                '{"revision":true,"searchWay":null}', '{"revision":2147483648,"searchWay":null}',
                '{"revision":[1],"searchWay":null}',
                '{"revision":1,"searchWay":"web"}' + (' ' * (1025 - '{"revision":1,"searchWay":"web"}'.length()))
        ]
    }

    def "a search marker refuses unknown query parameters and a caller outside the group without asking the store"() {
        given:
        holding(held)

        when:
        def request = sent(AT_VERSION + '/searching', '{"revision":1,"searchWay":null}')
        if (parameter != null) request.queryParam(parameter, '1')
        def answered = sending(request)

        then:
        answered.response.status == status
        documentOf(answered).get('code').asString() == code
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        held                      | parameter || status | code
        EnumSet.of(GroupRole.OWNER) | 'x'       || 400    | 'PARAMETER_UNKNOWN'
        EnumSet.noneOf(GroupRole)   | null      || 404    | 'GROUP_NOT_IN_VIEW'
    }

    def "writes a drawing beside the revision read and answers with what that change read"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/drawing", body))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        writtenAlone {
            it.draw(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 3, drawing)
        }

        where:
        body                                                  || drawing
        '{"revision":3,"drawing":"## {{complaint}}"}'       || "## {{complaint}}"
        '{"revision":3,"drawing":null}'                     || null
    }

    def "a drawing takes a visible character at its limit and keeps direction controls and tags"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/drawing", JSON.writeValueAsString([revision: 3, drawing: drawing])))

        then:
        answered.response.status == 200
        writtenAlone {
            it.draw(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 3, drawing)
        }

        where:
        drawing << ["a", "a" * 8192, new String(Character.toChars(0x1F642)) * 8192, "a\n\tb", "a\u202Eb", "a" + new String(Character.toChars(0xE0041)) + "b"]
    }

    def "a drawing whose lists and quotes, or emphasis, are 32 deep is written as sent"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/drawing", JSON.writeValueAsString([revision: 3, drawing: drawing])))

        then:
        answered.response.status == 200
        writtenAlone {
            it.draw(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 3, drawing)
        }

        where:
        drawing << [">" * 32 + " {{complaint}}", "  " * 31 + "- {{complaint}}", "> " * 16 + "- " * 16 + "a",
                    "- a\n" + "  " * 31 + "- b", "*" * 65, "*a " * 32 + "a* " * 32, "_a " * 64,
                    ">" * 32 + " " + "_" * 65]
    }

    def "a drawing is refused at arrival when it cannot be shown, exceeds its limit or nests too deep"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/drawing", JSON.writeValueAsString([revision: 3, drawing: drawing])))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        drawing                                       || code
        ""                                            || "DRAWING_UNUSABLE"
        " "                                           || "DRAWING_UNUSABLE"
        "\n\t"                                        || "DRAWING_UNUSABLE"
        "a" * 8193                                    || "DRAWING_UNUSABLE"
        new String(Character.toChars(0x1F642)) * 8193 || "DRAWING_UNUSABLE"
        "a\r\nb"                                      || "PROSE_LINE_BREAK_CRLF"
        "a\rb"                                        || "DRAWING_UNUSABLE"
        "a\u0007b"                                    || "DRAWING_UNUSABLE"
        "\u202E"                                      || "DRAWING_UNUSABLE"
        new String(Character.toChars(0xE0041))        || "DRAWING_UNUSABLE"
        ">" * 33                                      || "DRAWING_TOO_DEEP"
        "  " * 32 + "- a"                             || "DRAWING_TOO_DEEP"
        "a\n" + "> " * 33 + "a"                       || "DRAWING_TOO_DEEP"
        String.valueOf(0xFEFF as char) + ">" * 33     || "DRAWING_TOO_DEEP"
        "*" * 66                                      || "DRAWING_TOO_DEEP"
        "_" * 66                                      || "DRAWING_TOO_DEEP"
        "**\n\n" + "_a " * 66                         || "DRAWING_TOO_DEEP"
        ">" * 33 + "a" * 8160                         || "DRAWING_UNUSABLE"
        "*" * 66 + "a" * 8127                         || "DRAWING_UNUSABLE"
        ">" * 33 + "\u0007"                           || "DRAWING_UNUSABLE"
        ">" * 33 + "\r\na"                            || "PROSE_LINE_BREAK_CRLF"
        "*" * 66 + "\r\na"                            || "PROSE_LINE_BREAK_CRLF"
    }

    def "a drawing write refuses an unusable body before it reaches the draft"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(sent(AT_VERSION + "/drawing", body))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        body << ['{"revision":3,"drawing":3}', '{"revision":3,"drawing":null,"other":1}',
                 '{"revision":3}', '{"revision":0,"drawing":null}']
    }

    def "writes the half the address names, read as the body declares it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(AT_VERSION + "/" + side.published(), body))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        writtenAlone {
            it.declare(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 3,
                    new Declaration(side, Demands.ofQuestion(side), [field]), [readAs])
        }

        where:
        side                  | fields                                                                                                                                                           || field                                                                                                                                         | readAs
        DeclarationSide.TAKES | '[{"name":"urgent","label":null,"help":null,"kind":"yes_no","many":false,"most":null,"mustBeGiven":false}]'                                                       || new Field(new FieldName("urgent"), null, null, new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(), new Demand.Given(false))           | null
        DeclarationSide.TAKES | '[{"fieldId":null,"name":"urgent","label":null,"help":null,"kind":"yes_no","many":false,"most":null,"mustBeGiven":false}]'                                        || new Field(new FieldName("urgent"), null, null, new FieldShape.Plain(FieldKind.YES_NO), new HowMany.One(), new Demand.Given(false))           | null
        DeclarationSide.GIVES | '[{"name":"summary","label":null,"help":null,"kind":"text","many":false,"most":null,"longest":1000,"mustBeGiven":true,"stands":"always","floor":null}]'                           || new Field(new FieldName("summary"), null, null, new FieldShape.Text(1000), new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null)) | null
        DeclarationSide.GIVES | '[{"fieldId":"' + KEYS[0] + '","name":"summary","label":null,"help":null,"kind":"text","many":false,"most":null,"longest":1000,"mustBeGiven":true,"stands":"always","floor":null}]' || new Field(new FieldName("summary"), null, null, new FieldShape.Text(1000), new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null)) | KEYS[0]
        DeclarationSide.GIVES | '[{"fieldId":"not-a-key","name":"summary","label":null,"help":null,"kind":"text","many":false,"most":null,"longest":1000,"mustBeGiven":false,"stands":"always","floor":null}]'     || new Field(new FieldName("summary"), null, null, new FieldShape.Text(1000), new HowMany.One(), new Demand.Stands(false, FieldStanding.ALWAYS, null)) | null

        body = '{"revision":3,"fields":' + fields + '}'
    }

    /** Whatever the caller holds in the estate, holding nothing here reaches no version of the group's. */
    def "refuses a write by a caller in no role here as no group, never asking the store"() {
        given:
        holding(held)

        when:
        def answered = sending(sent(AT_VERSION + suffix, body))

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        held                         | suffix         | body                                  || status | code
        EnumSet.noneOf(GroupRole)    | "/instruction" | '{"revision":1,"instruction":null}'  || 404    | "GROUP_NOT_IN_VIEW"
        EnumSet.noneOf(GroupRole)    | "/takes"       | '{"revision":1,"fields":[]}'         || 404    | "GROUP_NOT_IN_VIEW"
        EnumSet.noneOf(GroupRole)    | "/drawing"     | '{"revision":1,"drawing":null}'      || 404    | "GROUP_NOT_IN_VIEW"
    }

    def "refuses an address naming no version alike whichever identifier names nothing, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "VERSION_NOT_IN_VIEW"

        and:
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        request << [get("/api/groups/${GROUP}/questions/not-an-entry/versions/${VERSION}"),
                    get("/api/groups/${GROUP}/questions/${ENTRY}/versions/not-a-version"),
                    sent("/api/groups/${GROUP}/questions/${ENTRY}/versions/not-a-version/instruction",
                            '{"revision":1,"instruction":null}'),
                    sent("/api/groups/${GROUP}/questions/not-an-entry/versions/${VERSION}/gives",
                            '{"revision":1,"fields":[]}')]
    }

    def "refuses what a request sends that its address does not take, under that rule's code, never asking the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        request                                                                                        || code
        get(AT_VERSION).queryParam("side", "takes")                                                    || "PARAMETER_UNKNOWN"
        sent(AT_VERSION + "/instruction", '{"revision":1,"instruction":null}').queryParam("x", "1")    || "PARAMETER_UNKNOWN"
        sent(AT_VERSION + "/takes", '{"revision":1,"fields":[]}').queryParam("x", "1")                 || "PARAMETER_UNKNOWN"
        sent(AT_VERSION + "/gives", '{"revision":1,"fields":[]}').queryParam("side", "takes")          || "PARAMETER_UNKNOWN"
        sent(AT_VERSION + "/instruction", '{"revision":1,"instruction":3}')                            || "BODY_UNUSABLE"
        sent(AT_VERSION + "/instruction", '{"revision":1,"instruction":null,"note":null}')             || "BODY_UNUSABLE"
        sent(AT_VERSION + "/instruction", '{"instruction":null}')                                      || "BODY_UNUSABLE"
        sent(AT_VERSION + "/instruction", '{"instruction":null,"note":1}')                             || "BODY_UNUSABLE"
        sent(AT_VERSION + "/instruction", '["Say."]')                                                  || "BODY_UNUSABLE"
        sent(AT_VERSION + "/instruction", '{"revision":1,"instruction":"Say.\\r\\nWhy."}')             || "PROSE_LINE_BREAK_CRLF"
        sent(AT_VERSION + "/instruction", '{"revision":1,"instruction":"Say.\\rWhy."}')                || "INSTRUCTION_UNUSABLE"
        sent(AT_VERSION + "/instruction", escaping("Say", [0x0D, 0x0A, 0x202E], "why."))               || "PROSE_LINE_BREAK_CRLF"
        sent(AT_VERSION + "/instruction", escaping("Say ", [0x202E], "why."))                          || "PROSE_DIRECTION_CONTROL"
        sent(AT_VERSION + "/instruction", escaping("Say ", [0x2066], "why."))                          || "PROSE_DIRECTION_CONTROL"
        sent(AT_VERSION + "/instruction", escaping("Say ", [0xDB40, 0xDC41], "why."))                  || "PROSE_TAG_CHARACTER"
        sent(AT_VERSION + "/instruction", '{"revision":1,"instruction":"\\n\\t"}')                     || "INSTRUCTION_UNUSABLE"
        sent(AT_VERSION + "/instruction", escaping("Bell", [0x0007], "rung"))                          || "INSTRUCTION_UNUSABLE"
        sent(AT_VERSION + "/takes", '[]')                                                              || "BODY_UNUSABLE"
        sent(AT_VERSION + "/takes", '{"fields":[]}')                                                   || "BODY_UNUSABLE"
        sent(AT_VERSION + "/takes", '{"revision":1}')                                                  || "BODY_UNUSABLE"
        sent(AT_VERSION + "/takes", '{"revision":1,"fields":{}}')                                      || "BODY_UNUSABLE"
        sent(AT_VERSION + "/takes", '{"revision":1,"fields":[],"note":null}')                          || "BODY_UNUSABLE"
        sent(AT_VERSION + "/takes", '{"revision":1,"fields":[{"fieldId":3,"name":"urgent","label":null,"help":null,"kind":"yes_no","many":false,"most":null,"mustBeGiven":false}]}') || "BODY_UNUSABLE"
        sent(AT_VERSION + "/takes", '{"revision":1,"fields":[{"name":"Urgent","label":null,"help":null,"kind":"yes_no","many":false,"most":null,"mustBeGiven":false}]}') || "FIELD_NAME_UNUSABLE"
    }

    /** Anything no reading of a draft could give back is refused at the edge, whichever write sends it. */
    def "refuses a revision that is no whole number from 1 up, whichever write sends it, never asking the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(sent(AT_VERSION + suffix, '{"revision":' + revision + ',' + member + '}'))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"

        and:
        Mockito.verifyNoInteractions(drafts, questions)

        where:
        [suffix, member, revision] << [
                [["/instruction", '"instruction":null'], ["/gives", '"fields":[]']],
                ["0", "-1", "1.5", "1.0", '"1"', "null", "true", "2147483648", "[1]"]
        ].combinations().collect { pair, revision -> pair + [revision] }
    }

    /** What the store refused is answered as the store refused it; the version is not read again. */
    def "answers a write the store refuses with that refusal, and reads nothing after it"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        willThrow(refusal.raised()).given(drafts).declare(
                new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 2,
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), []), [])

        when:
        def answered = sending(sent(AT_VERSION + "/takes", '{"revision":2,"fields":[]}'))

        then:
        answered.response.status == 409
        documentOf(answered).get("code").asString() == refusal.code().name()
        Mockito.verifyNoInteractions(questions)

        where:
        refusal << [LibraryRefusal.VERSION_STANDING_REFUSES, LibraryRefusal.DRAFT_WRITTEN_SINCE_READ]
    }

    /** The question kind's own segment, and the halves each under the spelling they are published by. */
    def "a question version and its halves are addressed under the spellings their vocabularies publish"() {
        expect:
        QuestionController.VERSION.contains("/" + EntryKind.QUESTION.segment() + "/")
        (QuestionController.HALF =~ /\{side:([^}]*)}/)[0][1].split(/\|/) as List ==
                DeclarationSide.values()*.published()
    }
}
