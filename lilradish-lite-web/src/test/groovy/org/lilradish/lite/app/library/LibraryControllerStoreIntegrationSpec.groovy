package org.lilradish.lite.app.library

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import jakarta.servlet.http.HttpServletRequest
import javax.sql.DataSource
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.change.ChangeTransactions
import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.files.FileDeployment
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.declaration.AskedField
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.Envelope
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.testutil.library.LibraryStore
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
        version.get("acts").collect { it.asString() } == ["write", "submit"]
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
                                                           pinned: [versionId: retired, number: 1],
                                                           newestInService: [versionId: newest, number: 2]]]

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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
        store.texts("select text_lines from declaration_fields where entry_version_id = ?::uuid and side = 'takes'", draft) == ["one"]
        document.gives*.list*.name == ["Claim types"]
        document.added == Envelope.toProduce([new AskedField(new FieldName("kind"), FieldKind.TERM,
                null, null, new OfferedTerms([], new ListNote("Choose what is claimed.")), [], true, true)])

        when:
        def submitted = sending(put(address + "/submission"))

        then:
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
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
        // DB-SPECIFIC: PostgreSQL :: casts to uuid.
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
                [entryId: list, kind: "reference_list", name: "Claim sorts", pinned: [versionId: retired, number: 1]]]
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
        EmbeddedPostgres server() {
            EmbeddedPostgres.builder().start()
        }

        @Bean
        LibraryStore store(EmbeddedPostgres server) {
            LibraryStore.template(server)
            def store = LibraryStore.copied(server, "library_answers")
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
