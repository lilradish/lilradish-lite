package org.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.mockito.BDDMockito.willThrow
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentPlace
import org.lilradish.lite.domain.registry.ContentProblem
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryAct
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
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
 * What submitting, withdrawing, approving and retiring a version are answered with, over a real
 * dispatcher: which act on which version of which entry the request was turned into, what each answers
 * with, every refusal met before the store is asked, and what a refusal for pinning versions retired
 * since carries beside its code.
 *
 * <p>The store is replaced, and so is who is calling and what they hold in the group.
 */
@WebMvcTest([LibraryController, EntryChangesController, VersionChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class VersionChangesControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000e21")

    static final UUID ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000e21")

    static final UUID VERSION = UUID.fromString("00000007-0000-4000-8000-000000000e21")

    static final UUID PINNED_ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000e22")

    static final UUID PINNED = UUID.fromString("00000007-0000-4000-8000-000000000e22")

    static final UUID NEWEST = UUID.fromString("00000007-0000-4000-8000-000000000e23")

    static final UUID UNREPLACED_ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000e24")

    static final UUID UNREPLACED = UUID.fromString("00000007-0000-4000-8000-000000000e24")

    static final String WORKFLOWS = "/api/groups/${GROUP}/workflows"

    static final String AT_VERSION = "${WORKFLOWS}/${ENTRY}/versions/${VERSION}"

    static final Library.EntryView READ = new Library.EntryView(new EntryId(ENTRY), EntryKind.WORKFLOW,
            new EntryName("Handle"), null, null, EnumSet.noneOf(EntryAct), [])

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
        given(library.entry(new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), READER)).willReturn(READ)
    }

    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private static MockHttpServletRequestBuilder asking(String method, String address) {
        method == "PUT" ? put(address) : delete(address)
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void changedThenRead(Closure<?> change) {
        def order = Mockito.inOrder(versions, library)
        change(order.verify(versions))
        order.verify(library).entry(new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), READER)
        Mockito.verifyNoMoreInteractions(versions, entries, library)
    }

    def "moves a version on as asked, answering with its entry as the library now reads it"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(asking(method, AT_VERSION + suffix))

        then:
        answered.response.status == 200
        documentOf(answered).get("entryId").asString() == ENTRY.toString()
        documentOf(answered).get("kind").asString() == "workflow"

        and: "the change made, and only then the entry read"
        changedThenRead {
            it."${act}"(
                    new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), new EntryVersionId(VERSION), READER)
        }

        where:
        method   | suffix         || act
        "PUT"    | "/submission"  || "submit"
        "DELETE" | "/submission"  || "withdraw"
        "PUT"    | "/approval"    || "approve"
        "PUT"    | "/retirement"  || "retire"
    }

    /** The permission is the act's: writing a version does not reach approving it, nor retiring it. */
    def "refuses a member whose roles there do not reach the act, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(put(AT_VERSION + suffix))

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"

        and:
        Mockito.verifyNoInteractions(versions, entries, library)

        where:
        suffix << ["/approval", "/retirement"]
    }

    /** Either identifier naming nothing names no version, and is answered as one the store does not hold. */
    def "refuses an address naming no version alike whichever identifier names nothing, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(put("${WORKFLOWS}/${entry}/versions/${version}/submission"))

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "VERSION_NOT_IN_VIEW"
        documentOf(answered).get("detail").asString() == "That version is not in this group's library."

        and:
        Mockito.verifyNoInteractions(versions, entries, library)

        where:
        entry             | version
        "not-an-entry"    | VERSION.toString()
        ENTRY.toString()  | "not-a-version"
    }

    def "refuses a body or a parameter an act does not take, and never asks the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(versions, entries, library)

        where:
        request                                                                                  || code
        put(AT_VERSION + "/approval").contentType(MediaType.APPLICATION_JSON).content("{}")     || "BODY_UNUSABLE"
        delete(AT_VERSION + "/submission").queryParam("reason", "late")                          || "PARAMETER_UNKNOWN"
    }

    /**
     * Each pin as what a member reads already, beside the sentence and never in it: the entry by its
     * identifier, kind and name, the version pinned, and the newest of that entry in service where one is.
     * The code, the status and the sentence are the ones any refusal of that code carries.
     */
    def "refuses a version pinning versions retired since, naming each pin and what would replace it"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        willThrow(new RetiredPinsRefusal([
                new RetiredPinsRefusal.RetiredPin(new EntryId(PINNED_ENTRY), EntryKind.QUESTION,
                        new EntryName("Triage"), new RetiredPinsRefusal.NumberedVersion(new EntryVersionId(PINNED), 1),
                        new RetiredPinsRefusal.NumberedVersion(new EntryVersionId(NEWEST), 3)),
                new RetiredPinsRefusal.RetiredPin(new EntryId(UNREPLACED_ENTRY), EntryKind.REFERENCE_LIST,
                        new EntryName("Regions"),
                        new RetiredPinsRefusal.NumberedVersion(new EntryVersionId(UNREPLACED), 2), null)
        ])).given(versions)."${act}"(
                new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), new EntryVersionId(VERSION), READER)

        when:
        def answered = sending(put(AT_VERSION + suffix))
        def document = documentOf(answered)

        then:
        answered.response.status == 409
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        document.get("code").asString() == "VERSION_PINS_RETIRED"
        document.get("detail").asString() == "This version pins a version retired since."
        document.get("instance").asString() == AT_VERSION + suffix

        and: "each pin named, the replacement left out where there is none"
        JSON.convertValue(document.get("pins"), List) == [
                [entryId: PINNED_ENTRY.toString(), kind: "question", name: "Triage",
                 pinned: [versionId: PINNED.toString(), number: 1],
                 newestInService: [versionId: NEWEST.toString(), number: 3]],
                [entryId: UNREPLACED_ENTRY.toString(), kind: "reference_list", name: "Regions",
                 pinned: [versionId: UNREPLACED.toString(), number: 2]]]

        and: "the sentence names none of them"
        !document.get("detail").asString().contains("Triage")

        and: "and the entry never read, nothing having changed"
        Mockito.verifyNoInteractions(library)

        where:
        suffix        || act
        "/submission" || "submit"
        "/approval"   || "approve"
    }

    /**
     * Each place as a member reads it already, beside the sentence and never in it: the problem's code, the
     * part it is in, the key of each field, term, step, case or binding naming the place under its own name, and
     * where the problem is a size how far past its bound it is, an asking's included past what a double holds.
     */
    def "refuses a submission whose content does not hold, naming every place in the order it was found"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        def field = UUID.fromString("0000000b-0000-4000-8000-000000000e21")
        def term = UUID.fromString("0000000a-0000-4000-8000-000000000e21")
        def step = UUID.fromString("0000000c-0000-4000-8000-000000000e21")
        def routeCase = UUID.fromString("0000000d-0000-4000-8000-000000000e21")
        def binding = UUID.fromString("0000000e-0000-4000-8000-000000000e21")
        willThrow(new ContentProblemsRefusal([
                new ContentProblem(
                        ContentProblemCode.INSTRUCTION_MISSING, new ContentPlace.Whole(ContentPart.INSTRUCTION), null),
                new ContentProblem(
                        ContentProblemCode.LONGEST_MISSING, new ContentPlace.AtField(ContentPart.GIVES, field), null),
                new ContentProblem(ContentProblemCode.TERM_REPEATED, new ContentPlace.AtTerm(term), null),
                new ContentProblem(
                        ContentProblemCode.LIMIT_PAST_LARGEST, new ContentPlace.AtField(ContentPart.GIVES, field), 12L),
                new ContentProblem(ContentProblemCode.RUNS_MISSING, new ContentPlace.AtStep(step), null),
                new ContentProblem(ContentProblemCode.CASE_REPEATED, new ContentPlace.AtCase(step, routeCase), null),
                new ContentProblem(
                        ContentProblemCode.INPUT_UNBOUND, new ContentPlace.AtInput(step, routeCase, field), null),
                new ContentProblem(
                        ContentProblemCode.SOURCE_UNKNOWN, new ContentPlace.AtBinding(ContentPart.GIVES, binding), null),
                new ContentProblem(ContentProblemCode.ASKING_PAST_LARGEST,
                        new ContentPlace.Whole(ContentPart.GIVES), 9_007_199_254_740_993L)
        ], [])).given(versions).submit(
                new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), new EntryVersionId(VERSION), READER)

        when:
        def answered = sending(put(AT_VERSION + "/submission"))
        def document = documentOf(answered)

        then:
        answered.response.status == 409
        document.get("code").asString() == "VERSION_CONTENT_DOES_NOT_HOLD"
        document.get("detail").asString() == "Some of what this version holds does not hold yet, and each place is named."
        JSON.convertValue(document.get("problems"), List) == [
                [code: "instruction_missing", part: "instruction"],
                [code: "longest_missing", part: "gives", fieldId: field.toString()],
                [code: "term_repeated", part: "terms", termId: term.toString()],
                [code: "limit_past_largest", part: "gives", fieldId: field.toString(), excess: 12],
                [code: "runs_missing", part: "steps", stepId: step.toString()],
                [code: "case_repeated", part: "steps", stepId: step.toString(), caseId: routeCase.toString()],
                [code: "input_unbound", part: "steps", fieldId: field.toString(), stepId: step.toString(),
                 caseId: routeCase.toString()],
                [code: "source_unknown", part: "gives", bindingId: binding.toString()],
                [code: "asking_past_largest", part: "gives", excess: 9_007_199_254_740_993L]]

        and: "no pins beside them, and the entry never read"
        !membersOf(document).contains("pins")
        Mockito.verifyNoInteractions(library)
    }

    /** A submission meeting both is told both at once, so neither has to be met before the other is seen. */
    def "refuses a submission whose content does not hold and whose pins are retired, naming both"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        willThrow(new ContentProblemsRefusal(
                [new ContentProblem(
                        ContentProblemCode.INSTRUCTION_MISSING, new ContentPlace.Whole(ContentPart.INSTRUCTION), null)],
                [new RetiredPinsRefusal.RetiredPin(new EntryId(UNREPLACED_ENTRY), EntryKind.REFERENCE_LIST,
                        new EntryName("Regions"),
                        new RetiredPinsRefusal.NumberedVersion(new EntryVersionId(UNREPLACED), 2), null)]
        )).given(versions).submit(
                new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), new EntryVersionId(VERSION), READER)

        when:
        def document = documentOf(sending(put(AT_VERSION + "/submission")))

        then:
        document.get("code").asString() == "VERSION_CONTENT_DOES_NOT_HOLD"
        JSON.convertValue(document.get("problems"), List) == [[code: "instruction_missing", part: "instruction"]]
        JSON.convertValue(document.get("pins"), List) == [
                [entryId: UNREPLACED_ENTRY.toString(), kind: "reference_list", name: "Regions",
                 pinned: [versionId: UNREPLACED.toString(), number: 2]]]
        !document.get("detail").asString().contains("Regions")
    }

    /** The pins are this refusal's alone; any other met by the same acts carries its code and nothing beside it. */
    def "answers every other refusal of these acts with no pins beside it"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        willThrow(LibraryRefusal.VERSION_STANDING_REFUSES.raised()).given(versions).submit(
                new GroupId(GROUP), EntryKind.WORKFLOW, new EntryId(ENTRY), new EntryVersionId(VERSION), READER)

        when:
        def answered = sending(put(AT_VERSION + "/submission"))

        then:
        answered.response.status == 409
        documentOf(answered).get("code").asString() == "VERSION_STANDING_REFUSES"
        !membersOf(documentOf(answered)).contains("pins")
        !membersOf(documentOf(answered)).contains("problems")
    }
}
