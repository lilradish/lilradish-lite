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
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
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

/**
 * What only a request reaching a real store can show about the whole path. Whether an entry is in view is
 * decided in SQL — another kind, another group's, one nobody holds are the same empty answer there — so
 * whether those refusals leave the server alike is a question about the path and not about either half of
 * it. A version refused for its pins is refused under a lock, with the pins read in the same statement
 * that finds them retired, so what the refusal carries is only known once that has run.
 *
 * <p>Only who is calling is replaced. Each feature arranges entries of its own, since what it changes is
 * committed.
 */
@WebMvcTest([LibraryController, EntryChangesController, VersionChangesController, QuestionController,
        ReferenceListController, WorkflowController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([Library, EntryChanges, VersionChanges, GroupRoles, ChangeTransactions, Store, ContentChecks,
        QuestionContentCheck, WorkflowContentCheck, ReferenceListContentCheck, Questions, QuestionDrafts, Drafts,
        ReferenceLists, ReferenceListDrafts, Workflows, WorkflowDrafts, ReleasedCodeSteps])
class LibraryControllerStoreIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000e81"

    static final String OTHER_GROUP = "00000003-0000-4000-8000-000000000e82"

    /** An overseer here and an owner there: what hides another group's entry is not whose group it is. */
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

    /** What a let-go hands on belongs to the runs, which this surface does not hold. */
    @MockitoBean
    private EntryLetGo letGo

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(ANN_USER))
        given(grants.heldBy(ANN_USER)).willReturn(EnumSet.noneOf(EstateRole))
    }

    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    /** Everything an answer says, but the address it was sent to, which is each caller's own. */
    private static List<Object> apartFromWhere(MvcResult answered) {
        def document = JSON.convertValue(JSON.readTree(answered.response.contentAsString), Map)
        [answered.response.status, answered.response.contentType, document.findAll { it.key != "instance" }]
    }

    /**
     * An entry of another kind, one of another group the caller is in, one nobody holds, and an address
     * that is no identifier: each read and each change of it answered exactly alike.
     */
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

    /** What a start answers with is what the address it names reads, there and then. */
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

    /**
     * The pin is a row written straight into the store, as no page this round can write one: a draft whose
     * field takes its terms from a list version since retired, that list having a newer version in service.
     */
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

    /**
     * Each write answers with the version as it then reads, and reading its address answers exactly the same:
     * the instruction, a half taken, and a half given back pinning a list of the group's in service. Whole,
     * it is submitted as any version is.
     */
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
                        '"most":null,"longest":4000,"mustBeGiven":true}]}'))
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
        document.gives*.list*.name == ["Claim types"]
        document.added == [[name: "kind", kind: "term", many: false, mustBeGiven: true, terms: [],
                            note: "Choose what is claimed.", confidence: true]]

        when:
        def submitted = sending(put(address + "/submission"))

        then:
        submitted.response.status == 200
        store.count("select count(*) from entry_version_submissions where entry_version_id = ?::uuid", draft) == 1
    }

    /** Saved from a page read before another save landed: refused whole, and what landed first is kept. */
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
        store.texts("select instruction from question_versions where entry_version_id = ?::uuid", draft) == ["First."]
    }

    /**
     * Two pages read a workflow draft at one revision and each writes a part of it: whichever lands second is
     * refused whole, whatever part it writes, and what landed first is kept as it landed.
     */
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
        store.texts("""
                select workflow.ceiling || ' ' || workflow.keeps_own_ceiling || ' ' || workflow.may_be_helped
                       || ' ' || version.revision
                  from workflow_versions workflow
                  join entry_versions version on version.entry_version_id = workflow.entry_version_id
                 where workflow.entry_version_id = ?::uuid
                """, draft) == ["100 true false 2"]
    }

    /**
     * One change at a time, each answered with the list as it then reads at the revision it was read at, and
     * reading its address answers the same. Two terms alike are written, and refused only at submitting.
     */
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
        store.count("select count(*) from entry_version_submissions where entry_version_id = ?::uuid", draft) == 1
    }

    private static Map answerOf(MvcResult answered) {
        assert answered.response.status == 200: answered.response.contentAsString
        JSON.convertValue(JSON.readTree(answered.response.contentAsString), Map)
    }

    /** Every place named beside the refusal, in the order the question reads, and the draft left a draft. */
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

    /** Neither refusal hides the other: the places and the pin retired since leave the server in one answer. */
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

    /**
     * Another group's question version, a workflow's version, one nobody holds and an address that is no
     * identifier: each read and each write of it answered exactly alike.
     */
    def "every way of naming no question version in view is answered alike, reading it or writing it"() {
        given:
        def elsewhere = "00000007-0000-4000-8000-000000000e8a"
        def handling = "00000007-0000-4000-8000-000000000e8b"
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

    /**
     * The real baseline on a real server, and the library the endpoints read and change through it, under
     * the transactions they would run under anywhere. Held as a configuration of this spec's own and not
     * scanned, so no other context starts a server for it.
     */
    @TestConfiguration(proxyBeanMethods = false)
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
