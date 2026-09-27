package org.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.registry.EntryAct
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryPurpose
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
 * What starting an entry, renaming it, stopping it, letting it go and starting its next draft are
 * answered with, over a real dispatcher: which change of which entry of which kind the request was turned
 * into, what each answers with, and every refusal met before the store is asked.
 *
 * <p>The store is replaced, and so is who is calling and what they hold in the group. Every request here
 * says it came from this application's own pages, which is the door's question and is asked of it
 * elsewhere.
 */
@WebMvcTest([LibraryController, EntryChangesController, VersionChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class EntryChangesControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000d01")

    static final UUID ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000d01")

    static final String QUESTIONS = "/api/groups/${GROUP}/questions"

    static final String TRIAGE = "${QUESTIONS}/${ENTRY}"

    static final Library.EntryView READ = new Library.EntryView(new EntryId(ENTRY), EntryKind.QUESTION,
            new EntryName("Triage"), new EntryPurpose("Sorts what comes in."), null, EnumSet.of(EntryAct.RENAME), [])

    static final String BODY_REFUSED =
            "This takes a JSON object holding an entry's name, and what it is for or null, and nothing else."

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private Library library

    @MockitoBean
    private EntryChanges entries

    @MockitoBean
    private VersionChanges versions

    private void holding(Set<GroupRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
        given(library.entry(new GroupId(GROUP), EntryKind.QUESTION, new EntryId(ENTRY), READER)).willReturn(READ)
    }

    /** As this application's own pages send it, which a browser says of every request it makes. */
    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private MvcResult describing(MockHttpServletRequestBuilder request, String body) {
        sending(request.contentType(MediaType.APPLICATION_JSON).content(body))
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    /**
     * The one change asked, and only once it has landed the entry read. Void, so the verification is what
     * fails rather than the null a mock's answer would assert as.
     */
    private void changedOnlyBy(Object store, Closure<?> asked) {
        def order = Mockito.inOrder(store, library)
        asked(order.verify(store))
        order.verify(library).entry(new GroupId(GROUP), EntryKind.QUESTION, new EntryId(ENTRY), READER)
        Mockito.verifyNoMoreInteractions(entries, versions, library)
    }

    /** The entry the library reads once the change has landed, and nothing else. */
    private static void assertIsTheEntryAsRead(MvcResult answered) {
        def document = documentOf(answered)
        assert document.get("entryId").asString() == ENTRY.toString()
        assert document.get("name").asString() == "Triage"
        assert document.get("acts").collect { it.asString() } == ["rename"]
    }

    /** Created, with the address the entry is read at, and the change handed the group the gate admitted. */
    def "starts an entry of the kind addressed, answering with it as the library reads it and where"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))
        given(entries.start(new GroupId(GROUP), EntryKind.QUESTION, new EntryName("Triage"), purpose, READER))
                .willReturn(new EntryId(ENTRY))

        when:
        def answered = describing(post(QUESTIONS), body)

        then:
        answered.response.status == 201
        answered.response.getHeader("Location") == TRIAGE
        assertIsTheEntryAsRead(answered)

        and:
        changedOnlyBy(entries) {
            it.start(new GroupId(GROUP), EntryKind.QUESTION, new EntryName("Triage"), purpose, READER)
        }

        where:
        body                                                  || purpose
        '{"name":"Triage","purpose":"Sorts what comes in."}'  || new EntryPurpose("Sorts what comes in.")
        '{"name":"Triage","purpose":null}'                    || null
    }

    /** One act changes both, so saying nothing of what it is for is saying it is for nothing. */
    def "renames an entry and says what it is for as one change, answering with it as the library now reads it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = describing(patch(TRIAGE), '{"purpose":null,"name":"Intake"}')

        then:
        answered.response.status == 200
        assertIsTheEntryAsRead(answered)

        and:
        changedOnlyBy(entries) {
            it.rename(new GroupId(GROUP), EntryKind.QUESTION, new EntryId(ENTRY), new EntryName("Intake"), null, READER)
        }
    }

    /** Each member is judged only once the body holds the two taken, each of a type it may be. */
    def "refuses a body it cannot read as a name and a purpose, under the code of whichever part is refused"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = describing(method == "POST" ? post(QUESTIONS) : patch(TRIAGE), body)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and: "no change asked of the store"
        Mockito.verifyNoInteractions(entries, versions)

        where:
        [method, body, code] << ["POST", "PATCH"].collectMany { method ->
            [["[]", "BODY_UNUSABLE"],
             ['{"name":"Triage"}', "BODY_UNUSABLE"],
             ['{"name":"Triage","purpose":null,"key":"T"}', "BODY_UNUSABLE"],
             ['{"name":7,"purpose":null}', "BODY_UNUSABLE"],
             ['{"name":"Triage","purpose":7}', "BODY_UNUSABLE"],
             ['{"name":"","purpose":null}', "ENTRY_NAME_UNUSABLE"],
             ['{"name":" Triage","purpose":null}', "ENTRY_NAME_UNUSABLE"],
             ['{"name":"' + "t" * 129 + '","purpose":null}', "ENTRY_NAME_UNUSABLE"],
             ['{"name":"Triage","purpose":""}', "ENTRY_PURPOSE_UNUSABLE"],
             ['{"name":"Triage","purpose":"' + "p" * 513 + '"}', "ENTRY_PURPOSE_UNUSABLE"]].collect { [method] + it }
        }
    }

    def "refuses the body in the sentence written for it, and each part in its own"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = describing(post(QUESTIONS), body)

        then:
        documentOf(answered).get("detail").asString() == sentence

        where:
        body                               || sentence
        "[]"                               || BODY_REFUSED
        '{"name":"","purpose":null}'       || LibraryRefusal.ENTRY_NAME_UNUSABLE.sentence()
        '{"name":"Triage","purpose":""}'   || LibraryRefusal.ENTRY_PURPOSE_UNUSABLE.sentence()
    }

    def "stops an entry and lets it go again, each answering with it as the library now reads it"() {
        given:
        holding(EnumSet.of(GroupRole.OVERSEER))

        when:
        def answered = sending(method == "PUT" ? put(TRIAGE + "/stop") : delete(TRIAGE + "/stop"))

        then:
        answered.response.status == 200
        assertIsTheEntryAsRead(answered)

        and:
        changedOnlyBy(entries) { it."${change}"(new GroupId(GROUP), EntryKind.QUESTION, new EntryId(ENTRY), READER) }

        where:
        method   || change
        "PUT"    || "stop"
        "DELETE" || "letGo"
    }

    /** Created, and with no address of its own, no version being read on its own. */
    def "starts the entry's next draft, answering with the entry as the library now reads it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(post(TRIAGE + "/versions"))

        then:
        answered.response.status == 201
        answered.response.getHeader("Location") == null
        assertIsTheEntryAsRead(answered)

        and:
        changedOnlyBy(versions) { it.startDraft(new GroupId(GROUP), EntryKind.QUESTION, new EntryId(ENTRY), READER) }
    }

    /** The permission is the act's, so a role that may write an entry is still refused a stop. */
    def "refuses a member whose roles there do not reach the act, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(method == "PUT" ? put(TRIAGE + "/stop") : delete(TRIAGE + "/stop"))

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"

        and:
        Mockito.verifyNoInteractions(entries, versions, library)

        where:
        method << ["PUT", "DELETE"]
    }

    /** An address naming no entry is refused as one the store does not hold, before the request is read further. */
    def "refuses a change at an address naming no entry, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "ENTRY_NOT_IN_VIEW"

        and:
        Mockito.verifyNoInteractions(entries, versions, library)

        where:
        request << [patch(QUESTIONS + "/not-an-entry").contentType(MediaType.APPLICATION_JSON).content("{}"),
                    put(QUESTIONS + "/not-an-entry/stop"), delete(QUESTIONS + "/not-an-entry/stop"),
                    post(QUESTIONS + "/not-an-entry/versions")]
    }

    def "refuses a body or a parameter a change asking nothing more does not take, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(entries, versions, library)

        where:
        request                                                                          || code
        put(TRIAGE + "/stop").contentType(MediaType.APPLICATION_JSON).content("{}")      || "BODY_UNUSABLE"
        delete(TRIAGE + "/stop").contentType(MediaType.APPLICATION_JSON).content("{}")   || "BODY_UNUSABLE"
        put(TRIAGE + "/stop").queryParam("until", "1")                                   || "PARAMETER_UNKNOWN"
        delete(TRIAGE + "/stop").queryParam("until", "1")                                || "PARAMETER_UNKNOWN"
        post(TRIAGE + "/versions").contentType(MediaType.APPLICATION_JSON).content("{}") || "BODY_UNUSABLE"
        post(TRIAGE + "/versions").queryParam("from", "1")                               || "PARAMETER_UNKNOWN"
        patch(TRIAGE).queryParam("kind", "workflow").contentType(MediaType.APPLICATION_JSON)
                .content('{"name":"Triage","purpose":null}')                             || "PARAMETER_UNKNOWN"
        post(QUESTIONS).queryParam("kind", "workflow").contentType(MediaType.APPLICATION_JSON)
                .content('{"name":"Triage","purpose":null}')                             || "PARAMETER_UNKNOWN"
    }
}
