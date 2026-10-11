package com.lilradish.lite.app.library

import static com.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import com.lilradish.lite.app.change.ChangeTransactions
import com.lilradish.lite.app.codestep.CodeSteps
import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.app.search.SearchWays
import com.lilradish.lite.domain.declaration.AskedField
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.OfferedTerms
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.inference.Envelope
import com.lilradish.lite.domain.model.ModelCatalog
import com.lilradish.lite.domain.referencelist.ListNote
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.transaction.PlatformTransactionManager
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/** Refusals for unseen versions must be indistinguishable over the whole request path. */
@WebMvcTest([LibraryController, EntryChangesController, VersionChangesController, QuestionController,
        ReferenceListController, WorkflowController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([Library, EntryChanges, VersionChanges, GroupRoles, ChangeTransactions, Store, ContentChecks,
        QuestionContentCheck, WorkflowContentCheck, ReferenceListContentCheck, Questions, QuestionDrafts, Drafts,
        ReferenceLists, ReferenceListDrafts, Workflows, WorkflowDrafts, ReleasedCodeSteps, SearchWays])
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LibraryControllerStoreIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000e81"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000e82"


    static final String ANN = "00000002-0000-4000-8000-000000000e81"

    static final UserId ANN_USER = new UserId("000e81")

    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private LibraryStore store

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants


    @MockitoBean
    private EntryLetGo letGo

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        given(grants.heldBy(ANN_USER)).willReturn(EnumSet.noneOf(EstateRole))
    }

    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }


    private static List<Object> apartFromWhere(MvcResult answered) {
        def document = JSON.convertValue(JSON.readTree(answered.response.contentAsString), Map)
        [answered.response.status, answered.response.contentType, document.findAll { it.key != "instance" }]
    }

    def "unknown instruction references are named in their first-reference order over HTTP without saving anything"() {
        given:
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        store.entry(entry, GROUP, "question", "Classify an imaginary claim ${entry}" as String)
        store.version(version, entry, 1, ANN)
        store.content(version, "question")
        def address = "/api/groups/${GROUP}/questions/${entry}/versions/${version}" as String
        def field = [name: "complaint", label: null, help: null, kind: "text", longest: 100, lines: "one",
                     many: false, most: null, mustBeGiven: true]
        def declared = answerOf(sending(put(address + "/takes").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([revision: 1, fields: [field]]))))
        def before = store.contents()

        when:
        def refused = sending(put(address + "/instruction").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([revision: declared.revision, instruction: instruction])))
        def document = JSON.convertValue(JSON.readTree(refused.response.contentAsString), Map)

        then:
        refused.response.status == 400
        document.code == "INSTRUCTION_NAMES_UNKNOWN"
        document.names == names
        document.detail == detail
        store.contents() == before

        where:
        instruction                                                         || names               | detail
        "Read {{unknown}} beside {{complaint}}."                             || ["unknown"]        | "{{unknown}} isn't an input of this question."
        "Read {{sender.name}}."                                              || ["sender.name"]    | "{{sender.name}} isn't an input of this question."
        "Read {{}}."                                                         || [""]               | "{{}} isn't an input of this question."
        "Read {{zeta}}, {{complaint}}, {{alpha}} and {{zeta}}; \\{{ignored}}." || ["zeta", "alpha"] | "{{zeta}} and {{alpha}} aren't inputs of this question."
    }

    def "inputs still referenced in instruction or drawing are named in sorted order over HTTP without saving anything"() {
        given:
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        store.entry(entry, GROUP, "question", "Describe an imaginary claim ${entry}" as String)
        store.version(version, entry, 1, ANN)
        store.content(version, "question")
        def address = "/api/groups/${GROUP}/questions/${entry}/versions/${version}" as String
        def fields = fieldNames.collect { name ->
            [name: name, label: null, help: null, kind: "text", longest: 100, lines: "one",
             many: false, most: null, mustBeGiven: true]
        }
        def declared = answerOf(sending(put(address + "/takes").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([revision: 1, fields: fields]))))
        def instructed = answerOf(sending(put(address + "/instruction").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([revision: declared.revision, instruction: instruction]))))
        def drawn = answerOf(sending(put(address + "/drawing").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([revision: instructed.revision, drawing: drawing]))))
        def before = store.contents()

        when:
        def refused = sending(put(address + "/takes").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([revision: drawn.revision, fields: []])))
        def document = JSON.convertValue(JSON.readTree(refused.response.contentAsString), Map)

        then:
        refused.response.status == 400
        document.code == "INPUT_STILL_MENTIONED"
        document.names == names
        document.detail == detail
        store.contents() == before

        where:
        fieldNames                | instruction                   | drawing        || names               | detail
        ["zeta", "alpha"]       | "Read {{zeta}}."              | null           || ["zeta"]           | "{{zeta}} is still mentioned in the instruction or how it is drawn; remove those mentions first."
        ["zeta", "alpha"]       | "Read the claim."             | "{{zeta}}"     || ["zeta"]           | "{{zeta}} is still mentioned in the instruction or how it is drawn; remove those mentions first."
        ["zeta", "alpha"]       | "Read {{zeta}} and {{alpha}}." | "{{zeta}}"     || ["alpha", "zeta"] | "{{alpha}} and {{zeta}} are still mentioned in the instruction or how it is drawn; remove those mentions first."
        ["claim", "claim_type"] | "Read {{claim}} and {{claim_type}}." | null       || ["claim_type", "claim"] | "{{claim_type}} and {{claim}} are still mentioned in the instruction or how it is drawn; remove those mentions first."
    }

    def "loaded version matching reads current facts without changing any stored rows or revision"() {
        given:
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        store.entry(entry, GROUP, "question", "An imaginary query")
        store.version(version, entry, 1, ANN)
        store.content(version, "question", "Classify an imaginary receipt.")
        def before = store.contents()
        def entryCount = store.count("select count(*) from entries")
        def versionCount = store.count("select count(*) from entry_versions")
        def revision = store.count("select revision from entry_versions where entry_version_id = ?", UUID.fromString(version))

        when:
        def matched = sending(post("/api/groups/${GROUP}/questions/version-name-matches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([text: "1", versions: [version]])))

        then:
        matched.response.status == 200
        JSON.readTree(matched.response.contentAsString).get("versions")*.asString() == [version]
        store.count("select count(*) from entries") == entryCount
        store.count("select count(*) from entry_versions") == versionCount
        store.count("select revision from entry_versions where entry_version_id = ?", UUID.fromString(version)) == revision
        store.contents() == before

        when:
        def crossSite = mockMvc.perform(post("/api/groups/${GROUP}/questions/version-name-matches")
                .header("Sec-Fetch-Site", "cross-site")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([text: "1", versions: [version]]))).andReturn()

        then:
        crossSite.response.status == 403
        JSON.readTree(crossSite.response.contentAsString).get("code").asString() == "ORIGIN_UNVERIFIED"
        store.contents() == before
    }


    def "the writer and a non-writer read identical version facts while actual writes still enforce their own rules"() {
        given:
        def writer = UUID.randomUUID().toString()
        def writerUser = new UserId("imaginary_writer")
        def operator = UUID.randomUUID().toString()
        def operatorUser = new UserId("imaginary_operator")
        def entry = UUID.randomUUID().toString()
        def version = UUID.randomUUID().toString()
        store.person(writer, writerUser.value(), "Ari Imaginary")
        store.person(operator, operatorUser.value(), "Greno Imaginary")
        store.member(GROUP, writer, "overseer")
        store.member(GROUP, operator, "operator")
        store.entry(entry, GROUP, "question", "Sort an imaginary receipt")
        store.version(version, entry, 1, writer)
        store.content(version, "question", "Say which category this imaginary receipt belongs to.")
        store.submitted(version, writer)
        given(grants.heldBy(writerUser)).willReturn(EnumSet.noneOf(EstateRole))
        given(grants.heldBy(operatorUser)).willReturn(EnumSet.noneOf(EstateRole))
        def address = "/api/groups/${GROUP}/questions/${entry}" as String
        def before = store.contents()
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(writerUser))

        when:
        def writerRead = sending(get(address))
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        def otherRead = sending(get(address))

        then:
        writerRead.response.status == 200
        otherRead.response.status == 200
        JSON.readTree(writerRead.response.contentAsString) == JSON.readTree(otherRead.response.contentAsString)
        JSON.readTree(writerRead.response.contentAsString).get("versions").get(0).get("stateActs").collect { it.asString() } as Set == ["withdraw", "approve"] as Set
        JSON.readTree(writerRead.response.contentAsString).get("versions").get(0).get("writers").get(0).get("userId").asString() == writerUser.value()
        store.contents() == before

        when:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(writerUser))
        def selfApproval = sending(put(address + "/versions/${version}/approval"))

        then:
        selfApproval.response.status == 403
        JSON.readTree(selfApproval.response.contentAsString).get("code").asString() == "APPROVER_WROTE_VERSION"
        store.contents() == before

        when:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(operatorUser))
        def stop = sending(put(address + "/stop"))

        then:
        stop.response.status == 403
        JSON.readTree(stop.response.contentAsString).get("code").asString() == "ACT_NOT_PERMITTED"
        store.contents() == before

        when:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        def approved = sending(put(address + "/versions/${version}/approval"))
        def approvedRead = sending(get(address))
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(writerUser))
        def approvedWriterRead = sending(get(address))

        then:
        approved.response.status == 200
        JSON.readTree(approved.response.contentAsString) == JSON.readTree(approvedRead.response.contentAsString)
        JSON.readTree(approvedRead.response.contentAsString) == JSON.readTree(approvedWriterRead.response.contentAsString)
        JSON.readTree(approvedWriterRead.response.contentAsString).get("versions").get(0).get("stateActs").collect { it.asString() } == ["retire"]
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select approved_by::text from entry_versions where entry_version_id = ?::uuid", version) == [ANN]
    }

    def "every way of naming no entry in view is answered alike, reading it or changing it"() {
        given:
        def addressed = ["00000006-0000-4000-8000-000000000e81", "00000006-0000-4000-8000-000000000e82",
                         "00000006-0000-4000-8000-000000000e89", "not-an-entry"]
        def before = store.contents()

        when:
        def answers = addressed.collect { entry ->
            def address = "/api/groups/${GROUP}/questions/${entry}${suffix}" as String
            sending(method == "GET" ? get(address) : put(address))
        }

        then:
        answers*.response*.status.toSet() == [404] as Set
        answers.collect { apartFromWhere(it) }.toSet().size() == 1
        JSON.readTree(answers.first().response.contentAsString).get("code").asString() == "ENTRY_NOT_IN_VIEW"

        and: "nothing changed by any of them"
        store.contents() == before

        where:
        method | suffix
        "GET"  | ""
        "PUT"  | "/stop"
    }


    def "starts an entry, and the address the answer names reads it exactly as the answer did"() {
        when:
        def started = sending(post("/api/groups/${GROUP}/reference-lists").contentType(MediaType.APPLICATION_JSON)
                .content('{"name":"Regions","purpose":"Where a claim came from."}'))
        def read = mockMvc.perform(get(started.response.getHeader("Location"))).andReturn()

        then:
        started.response.status == 201
        read.response.status == 200
        started.response.contentAsString == read.response.contentAsString

        and: "its first version a draft the caller wrote, which they may write and submit"
        def version = JSON.readTree(read.response.contentAsString).get("versions").get(0)
        version.get("standing").asString() == "draft"
        version.get("writers").collect { it.get("userId").asString() } == ["000e81"]
        version.get("stateActs").collect { it.asString() } == ["write", "submit"]
    }

    def "renames an entry of the kind addressed, and answers with the name and purpose it now holds"() {
        given:
        store.entry("00000006-0000-4000-8000-000000000e83", GROUP, "question", "Triage", "Sorts claims.")

        when:
        def renamed = sending(patch("/api/groups/${GROUP}/questions/00000006-0000-4000-8000-000000000e83")
                .contentType(MediaType.APPLICATION_JSON).content('{"name":"Intake","purpose":null}'))
        def document = JSON.readTree(renamed.response.contentAsString)

        then:
        renamed.response.status == 200
        document.get("name").asString() == "Intake"
        !document.has("purpose")
        store.texts("select name from entries where entry_id = '00000006-0000-4000-8000-000000000e83'") == ["Intake"]
    }


    def "refuses to submit a draft pinning a version retired since, naming the pin and the newest in service"() {
        given:
        def list = "00000006-0000-4000-8000-000000000e84"
        def retired = "00000007-0000-4000-8000-000000000e84"
        def newest = "00000007-0000-4000-8000-000000000e85"
        def workflow = "00000006-0000-4000-8000-000000000e86"
        def draft = "00000007-0000-4000-8000-000000000e86"
        store.entry(list, GROUP, "reference_list", "Claim kinds")
        store.seeded(retired, list, 1)
        store.content(retired, "reference_list")
        store.entry(workflow, GROUP, "workflow", "Route claims")
        store.version(draft, workflow, 1, ANN)
        store.content(draft, "workflow")
        store.termField("0000000b-0000-4000-8000-000000000e86", draft, "workflow", retired, "takes")
        store.entry("00000006-0000-4000-8000-000000000e8e", GROUP, "workflow", "Nothing beneath")
        store.seeded("00000007-0000-4000-8000-000000000e8e", "00000006-0000-4000-8000-000000000e8e", 1)
        store.content("00000007-0000-4000-8000-000000000e8e", "workflow")
        store.step("00000009-0000-4000-8000-000000000e86", draft, 1, "00000007-0000-4000-8000-000000000e8e", "workflow")
        store.retired(retired, FIRST_STEWARD)
        store.seeded(newest, list, 2)
        store.content(newest, "reference_list")
        def before = store.contents()
        def address = "/api/groups/${GROUP}/workflows/${workflow}/versions/${draft}/submission" as String

        when:
        def refused = sending(put(address))
        def document = JSON.readTree(refused.response.contentAsString)

        then:
        refused.response.status == 409
        document.get("code").asString() == "VERSION_PINS_RETIRED"
        document.get("instance").asString() == address
        JSON.convertValue(document.get("pins"), List) == [[entryId: list, kind: "reference_list", name: "Claim kinds",
                                                           pinned: [versionId: retired, number: 1, versionName: null],
                                                           newestInService: [versionId: newest, number: 2, versionName: null]]]

        and: "the draft left as it was"
        store.contents() == before
    }


    def "writes a question draft through its address, reads it as each write answered, and submits it once whole"() {
        given:
        def question = "00000006-0000-4000-8000-000000000e87"
        def draft = "00000007-0000-4000-8000-000000000e87"
        def list = "00000006-0000-4000-8000-000000000e88"
        def inService = "00000007-0000-4000-8000-000000000e88"
        store.entry(question, GROUP, "question", "Classify claims")
        store.version(draft, question, 1, ANN)
        store.content(draft, "question")
        store.entry(list, GROUP, "reference_list", "Claim types")
        store.seeded(inService, list, 1)
        store.content(inService, "reference_list", "Choose what is claimed.")
        def address = "/api/groups/${GROUP}/questions/${question}/versions/${draft}" as String

        when:
        def instructed = sending(put(address + "/instruction").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":1,"instruction":"Say what kind of claim it is."}'))
        def taken = sending(put(address + "/takes").contentType(MediaType.APPLICATION_JSON).content(
                '{"revision":2,"fields":[{"name":"claim","label":"The claim","help":null,"kind":"text","many":false,' +
                        '"most":null,"longest":4000,"lines":"one","mustBeGiven":true}]}'))
        def answering = sending(put(address + "/gives").contentType(MediaType.APPLICATION_JSON).content(
                '{"revision":3,"fields":[{"name":"kind","label":null,"help":null,"kind":"term","many":false,' +
                        '"most":null,"list":"' + inService + '","mustBeGiven":true,"stands":"above_confidence",' +
                        '"floor":75}]}'))
        def read = sending(get(address))

        then:
        [instructed, taken, answering, read]*.response*.status == [200, 200, 200, 200]
        read.response.contentAsString == answering.response.contentAsString
        [instructed, taken]*.response*.contentAsString.collect { JSON.readTree(it).get("revision").asInt() } == [2, 3]

        and: "each half as it was written, the list pinned named, and what a model is told of the answer"
        def document = JSON.convertValue(JSON.readTree(read.response.contentAsString), Map)
        document.revision == 4
        document.instruction == "Say what kind of claim it is."
        document.takes*.name == ["claim"]
        document.takes*.lines == ["one"]
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; declaration_side enum label 'takes'.
        store.texts("select text_lines from declaration_fields where entry_version_id = ?::uuid and side = 'takes'", draft) == ["one"]
        document.gives*.list*.name == ["Claim types"]
        document.added == Envelope.toProduce([new AskedField(new FieldName("kind"), FieldKind.TERM,
                null, null, new OfferedTerms([], new ListNote("Choose what is claimed.")), [], true, true)], new com.lilradish.lite.domain.declaration.Instruction(document.instruction))

        when:
        def submitted = sending(put(address + "/submission"))

        then:
        submitted.response.status == 200
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_submissions where entry_version_id = ?::uuid", draft) == 1
    }


    def "submission rechecks unknown instruction references and leaves the draft untouched until they name real inputs"() {
        given:
        def question = UUID.randomUUID().toString()
        def draft = UUID.randomUUID().toString()
        store.entry(question, GROUP, "question", "Sort an imaginary claim")
        store.version(draft, question, 1, ANN)
        store.wholeQuestion(draft)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.session.sql("update question_versions set instruction = 'Read {{missing.part}}.' where entry_version_id = ?::uuid")
                .param(draft).update()
        def address = "/api/groups/${GROUP}/questions/${question}/versions/${draft}" as String
        def before = store.contents()

        when:
        def refused = sending(put(address + "/submission"))

        then:
        refused.response.status == 409
        def refusal = JSON.convertValue(JSON.readTree(refused.response.contentAsString), Map)
        refusal.code == 'VERSION_CONTENT_DOES_NOT_HOLD'
        refusal.problems == [[code: 'instruction_names_unknown', part: 'instruction', name: 'missing.part']]
        store.contents() == before

        when:
        def corrected = sending(put(address + "/instruction").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":1,"instruction":"Read \\\\{{literal}} and {{unfinished."}'))
        def submitted = sending(put(address + "/submission"))

        then:
        corrected.response.status == 200
        submitted.response.status == 200
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_submissions where entry_version_id = ?::uuid", draft) == 1
    }

    def "a write naming a revision another write has passed since is refused as written since, and changes nothing"() {
        given:
        def question = "00000006-0000-4000-8000-000000000e90"
        def draft = "00000007-0000-4000-8000-000000000e90"
        store.entry(question, GROUP, "question", "Weigh claims")
        store.version(draft, question, 1, ANN)
        store.content(draft, "question")
        def address = "/api/groups/${GROUP}/questions/${question}/versions/${draft}/instruction" as String
        sending(put(address).contentType(MediaType.APPLICATION_JSON).content('{"revision":1,"instruction":"First."}'))
        def before = store.contents()

        when:
        def refused = sending(put(address).contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":1,"instruction":"Second."}'))

        then:
        refused.response.status == 409
        JSON.readTree(refused.response.contentAsString).get("code").asString() == "DRAFT_WRITTEN_SINCE_READ"
        store.contents() == before
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select instruction from question_versions where entry_version_id = ?::uuid", draft) == ["First."]
    }


    def "two workflow writes naming the same revision: the second is refused as written since, the first kept whole"() {
        given:
        def workflow = "00000006-0000-4000-8000-000000000e92"
        def draft = "00000007-0000-4000-8000-000000000e92"
        store.entry(workflow, GROUP, "workflow", "Settle claims")
        store.version(draft, workflow, 1, ANN)
        store.content(draft, "workflow")
        def address = "/api/groups/${GROUP}/workflows/${workflow}/versions/${draft}" as String

        when:
        def first = sending(put(address + "/ceiling").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":1,"ceiling":"100","keepsOwnCeiling":true,"raiseNeedsApproval":false}'))
        def before = store.contents()
        def second = sending(put(address + "/help").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":1,"mayBeHelped":true,"helper":null}'))

        then:
        first.response.status == 200
        JSON.readTree(first.response.contentAsString).get("revision").asInt() == 2
        second.response.status == 409
        JSON.readTree(second.response.contentAsString).get("code").asString() == "DRAFT_WRITTEN_SINCE_READ"

        and: "the first write's content and revision stand, and nothing of the second landed"
        store.contents() == before
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; || converts bigint, boolean and integer operands to text.
        store.texts("""
                select workflow.ceiling || ' ' || workflow.keeps_own_ceiling || ' ' || workflow.may_be_helped
                       || ' ' || version.revision
                  from workflow_versions workflow
                  join entry_versions version on version.entry_version_id = workflow.entry_version_id
                 where workflow.entry_version_id = ?::uuid
                """, draft) == ["100 true false 2"]
    }


    def "writes a reference list draft change by change through its address, and submits it once its terms hold"() {
        given:
        def list = "00000006-0000-4000-8000-000000000e91"
        def draft = "00000007-0000-4000-8000-000000000e91"
        store.entry(list, GROUP, "reference_list", "Claim channels")
        store.version(draft, list, 1, ANN)
        store.content(draft, "reference_list")
        def address = "/api/groups/${GROUP}/reference-lists/${list}/versions/${draft}" as String

        when:
        def first = answerOf(sending(post(address + "/terms").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":1,"term":"Email","meaning":"It came by email."}')))
        def second = answerOf(sending(post(address + "/terms").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":2,"term":"email","meaning":"It came by email again."}')))
        def alike = sending(put(address + "/submission"))
        def repeated = second.terms[1].termId

        then:
        [first.revision, second.revision] == [2, 3]
        second.terms*.term == ["Email", "email"]
        alike.response.status == 409
        JSON.convertValue(JSON.readTree(alike.response.contentAsString).get("problems"), List) ==
                [[code: "term_repeated", part: "terms", termId: repeated]]

        when:
        def edited = answerOf(sending(put(address + "/terms/" + repeated).contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":3,"term":"Phone","meaning":"It came by phone."}')))
        def moved = answerOf(sending(post(address + "/terms/" + repeated + "/up")
                .contentType(MediaType.APPLICATION_JSON).content('{"revision":4}')))
        def noted = answerOf(sending(put(address + "/note").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":5,"note":"Choose how it first came."}')))
        def added = answerOf(sending(post(address + "/terms").contentType(MediaType.APPLICATION_JSON)
                .content('{"revision":6,"term":"Post","meaning":"It came by post."}')))
        def removedResult = sending(post(address + "/terms/" + added.terms[2].termId + "/removal")
                .contentType(MediaType.APPLICATION_JSON).content('{"revision":7}'))
        def read = sending(get(address))

        then:
        [edited, moved, noted, added]*.revision == [4, 5, 6, 7]
        edited.terms*.term == ["Email", "Phone"]
        moved.terms*.term == ["Phone", "Email"]
        moved.terms*.termId == [repeated, first.terms[0].termId]
        read.response.contentAsString == removedResult.response.contentAsString
        answerOf(read) == [revision: 8, note: "Choose how it first came.",
                           terms   : [[termId: repeated, term: "Phone", meaning: "It came by phone."],
                                      [termId: first.terms[0].termId, term: "Email", meaning: "It came by email."]]]

        when:
        def submitted = sending(put(address + "/submission"))

        then:
        submitted.response.status == 200
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_submissions where entry_version_id = ?::uuid", draft) == 1
    }

    def "a workflow's real write names a several-to-one binding and refuses submission, while one-to-several is submitted"() {
        given:
        def ending = source == "several" ? "ea1" : "ea2"
        def question = "00000006-0000-4000-8000-000000000${ending}" as String
        def pinned = "00000007-0000-4000-8000-000000000${ending}" as String
        def workflow = "00000006-0000-4000-8000-000000000${ending == 'ea1' ? 'ea3' : 'ea4'}" as String
        def draft = "00000007-0000-4000-8000-000000000${ending == 'ea1' ? 'ea3' : 'ea4'}" as String
        store.entry(question, GROUP, "question", "Demo Sample ${source} question" as String)
        store.seeded(pinned, question, 1)
        store.wholeQuestion(pinned)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid; entry_kind/declaration_side/field_kind enum labels 'question'/'takes'/'text'.
        store.session.sql("""
                insert into declaration_fields (entry_version_id, entry_kind, side, position, name, kind,
                                                text_limit, text_lines, must_be_given, created_by)
                values (?::uuid, 'question', 'takes', 1, 'title', 'text', 40, ?, true, ?::uuid)
                """).params(pinned, target, LibraryStore.SEEDER).update()
        store.entry(workflow, GROUP, "workflow", "Demo Sample ${source} workflow" as String)
        store.version(draft, workflow, 1, ANN)
        store.content(draft, "workflow")
        def address = "/api/groups/${GROUP}/workflows/${workflow}/versions/${draft}" as String
        def text = [name: "title", label: null, help: null, kind: "text", longest: 40, lines: source,
                    many: false, most: null, mustBeGiven: true]
        def declaration = sending(put(address + "/takes").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString([revision: 1, fields: [text]])))
        assert declaration.response.status == 200
        def body = [revision: 2, steps: [[name: "ask", runs: [kind: "question", version: pinned],
                    producer: [kind: "person"], tries: 1, reviewer: null,
                    bindings: [[target: "title", source: [input: "title"]]]]], outputs: []]

        when:
        def written = sending(put(address + "/steps").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)))
        def read = answerOf(written)
        def before = store.contents()
        def submitted = sending(put(address + "/submission"))

        then:
        written.response.status == 200
        read.takes*.lines == [source]
        read.steps[0].runs.takes*.lines == [target]
        read.problems == (fits ? [] : [[code: "source_does_not_fit", part: "steps",
                                       bindingId: read.steps[0].bindings[0].bindingId]])
        submitted.response.status == (fits ? 200 : 409)
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.count("select count(*) from entry_version_submissions where entry_version_id = ?::uuid", draft) == (fits ? 1 : 0)
        if (!fits) {
            JSON.readTree(submitted.response.contentAsString).get("code").asString() == "VERSION_CONTENT_DOES_NOT_HOLD"
            store.contents() == before
        }

        where:
        source    | target    || fits
        "several" | "one"     || false
        "one"     | "several" || true
    }

    private static Map answerOf(MvcResult answered) {
        assert answered.response.status == 200: answered.response.contentAsString
        JSON.convertValue(JSON.readTree(answered.response.contentAsString), Map)
    }


    def "refuses to submit a question whose content does not hold, naming every place, and changes nothing"() {
        given:
        def question = "00000006-0000-4000-8000-000000000e89"
        def draft = "00000007-0000-4000-8000-000000000e89"
        store.entry(question, GROUP, "question", "Summarise claims")
        store.version(draft, question, 1, ANN)
        store.content(draft, "question")
        def before = store.contents()

        when:
        def refused = sending(put("/api/groups/${GROUP}/questions/${question}/versions/${draft}/submission"))
        def document = JSON.readTree(refused.response.contentAsString)

        then:
        refused.response.status == 409
        document.get("code").asString() == "VERSION_CONTENT_DOES_NOT_HOLD"
        JSON.convertValue(document.get("problems"), List) == [
                [code: "instruction_missing", part: "instruction"],
                [code: "nothing_given_back", part: "gives"]]
        store.contents() == before
    }


    def "refuses to submit a question whose content does not hold and whose list is retired, naming both"() {
        given:
        def question = "00000006-0000-4000-8000-000000000e8c"
        def draft = "00000007-0000-4000-8000-000000000e8c"
        def list = "00000006-0000-4000-8000-000000000e8d"
        def retired = "00000007-0000-4000-8000-000000000e8d"
        def field = "0000000b-0000-4000-8000-000000000e8c"
        store.entry(question, GROUP, "question", "Sort claims")
        store.version(draft, question, 1, ANN)
        store.content(draft, "question")
        store.entry(list, GROUP, "reference_list", "Claim sorts")
        store.seeded(retired, list, 1, true)
        store.content(retired, "reference_list")
        store.termField(field, draft, "question", retired)
        def before = store.contents()

        when:
        def refused = sending(put("/api/groups/${GROUP}/questions/${question}/versions/${draft}/submission"))
        def document = JSON.readTree(refused.response.contentAsString)

        then:
        refused.response.status == 409
        document.get("code").asString() == "VERSION_CONTENT_DOES_NOT_HOLD"
        JSON.convertValue(document.get("problems"), List) == [
                [code: "instruction_missing", part: "instruction"],
                [code: "standing_missing", part: "gives", fieldId: field]]
        JSON.convertValue(document.get("pins"), List) == [
                [entryId: list, kind: "reference_list", name: "Claim sorts", pinned: [versionId: retired, number: 1, versionName: null]]]
        store.contents() == before
    }


    def "every way of naming no question version in view is answered alike, reading it or writing it"() {
        given:
        def elsewhere = "00000007-0000-4000-8000-000000000e8a"
        def handling = "00000007-0000-4000-8000-000000000e8b"
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        if (store.count("select count(*) from entry_versions where entry_version_id = ?::uuid", elsewhere) == 0) {
            store.version(elsewhere, "00000006-0000-4000-8000-000000000e82", 1, ANN)
            store.content(elsewhere, "question")
            store.version(handling, "00000006-0000-4000-8000-000000000e81", 1, ANN)
            store.content(handling, "workflow")
        }
        def addressed = [["00000006-0000-4000-8000-000000000e82", elsewhere],
                         ["00000006-0000-4000-8000-000000000e81", handling],
                         ["00000006-0000-4000-8000-000000000e82", "00000007-0000-4000-8000-000000000e8f"],
                         ["00000006-0000-4000-8000-000000000e82", "not-a-version"]]
        def before = store.contents()

        when:
        def answers = addressed.collect { entry, version ->
            def address = "/api/groups/${GROUP}/questions/${entry}/versions/${version}${suffix}" as String
            sending(method == "GET" ? get(address)
                    : put(address).contentType(MediaType.APPLICATION_JSON).content('{"revision":1,"instruction":null}'))
        }

        then:
        answers*.response*.status.toSet() == [404] as Set
        answers.collect { apartFromWhere(it) }.toSet().size() == 1
        JSON.readTree(answers.first().response.contentAsString).get("code").asString() == "VERSION_NOT_IN_VIEW"
        answers.every { !JSON.readTree(it.response.contentAsString).has("names") }

        and: "nothing changed by any of them"
        store.contents() == before

        where:
        method | suffix
        "GET"  | ""
        "PUT"  | "/instruction"
    }

    /** TestConfiguration keeps fixture components out of other test contexts. */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FileDeployment)
    static class Store {

        @Bean(destroyMethod = "close")
        TestPostgres server() {
            TestPostgres.worker()
        }

        @Bean
        LibraryStore store(TestPostgres server) {
            LibraryStore.prepareBaseline(server)
            def store = LibraryStore.forCase(server, "library_answers")
            store.person(ANN, "000e81", "Ann Writer")
            store.group(GROUP, "CLAIMS", "Claims")
            store.group(OTHER_GROUP, "BILLING", "Billing")
            store.member(GROUP, ANN, "overseer")
            store.member(OTHER_GROUP, ANN, "owner")
            store.entry("00000006-0000-4000-8000-000000000e81", GROUP, "workflow", "Handle claims")
            store.entry("00000006-0000-4000-8000-000000000e82", OTHER_GROUP, "question", "Elsewhere")
            store
        }

        @Bean
        DataSource database(LibraryStore store) {
            store.database
        }

        @Bean
        ModelCatalog models() {
            LibraryStore.MODELS
        }

        @Bean
        CodeSteps codeSteps() {
            LibraryStore.CODE_STEPS
        }

        @Bean
        JdbcClient session(LibraryStore store) {
            store.session
        }

        @Bean
        PlatformTransactionManager transactions(LibraryStore store) {
            store.transactionManager()
        }
    }
}
