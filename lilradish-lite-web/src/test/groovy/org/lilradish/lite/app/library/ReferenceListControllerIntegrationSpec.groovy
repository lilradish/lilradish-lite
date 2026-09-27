package org.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.ArgumentMatchers.anyInt
import static org.mockito.BDDMockito.given
import static org.mockito.BDDMockito.willThrow
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryId
import org.lilradish.lite.domain.registry.EntryKind
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
 * What reading a reference list version and each change to its note and its terms are answered with, over a
 * real dispatcher: what each request was turned into, the document it answers with, and every refusal met
 * before the store is asked. The store is replaced, and so is who is calling and what they hold.
 */
@WebMvcTest(ReferenceListController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class ReferenceListControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000f31")

    static final UUID ENTRY = UUID.fromString("00000006-0000-4000-8000-000000000f31")

    static final UUID VERSION = UUID.fromString("00000007-0000-4000-8000-000000000f31")

    static final UUID BILLING = UUID.fromString("0000000a-0000-4000-8000-000000000f31")

    static final UUID DELIVERY = UUID.fromString("0000000a-0000-4000-8000-000000000f32")

    static final String AT_VERSION = "/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/${VERSION}"

    static final String AT_TERM = AT_VERSION + "/terms/${BILLING}"

    static final String TAKEN_TERM = '{"revision":1,"term":"Billing","meaning":"A charge."}'

    static final UUID BILLING_AGAIN = UUID.fromString("0000000a-0000-4000-8000-000000000f33")

    static final ReferenceLists.ListView READ = new ReferenceLists.ListView(5, new ListNote("Pick the nearest.\n\tAsk."), [
            new ReferenceLists.HeldTerm(BILLING, new Term("Billing"), new TermMeaning("A charge is disputed."), false),
            new ReferenceLists.HeldTerm(DELIVERY, new Term("Delivery"), new TermMeaning("It came late."), false),
            new ReferenceLists.HeldTerm(BILLING_AGAIN, new Term("BILLING"), new TermMeaning("Charged twice."), true)])

    /** Held whole, so a member added, dropped or renamed on either side of the wire is a change seen here. */
    static final Map ANSWER = [
            revision: 5,
            note    : "Pick the nearest.\n\tAsk.",
            terms   : [[termId: BILLING.toString(), term: "Billing", meaning: "A charge is disputed."],
                       [termId: DELIVERY.toString(), term: "Delivery", meaning: "It came late."],
                       [termId: BILLING_AGAIN.toString(), term: "BILLING", meaning: "Charged twice.", alikeEarlier: true]]]

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private ReferenceLists lists

    @MockitoBean
    private ReferenceListDrafts drafts

    private void holding(Set<GroupRole> held, ReferenceLists.ListView read = READ) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(held)
        given(lists.read(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER)).willReturn(read)
        given(drafts.note(any(), any(), any(), any(), anyInt(), any())).willReturn(read)
        given(drafts.add(any(), any(), any(), any(), anyInt(), any(), any())).willReturn(read)
        given(drafts.edit(any(), any(), any(), any(), anyInt(), any(), any(), any())).willReturn(read)
        given(drafts.remove(any(), any(), any(), any(), anyInt(), any())).willReturn(read)
        given(drafts.move(any(), any(), any(), any(), anyInt(), any(), any())).willReturn(read)
    }

    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private static MockHttpServletRequestBuilder sent(MockHttpServletRequestBuilder request, String body) {
        request.contentType(MediaType.APPLICATION_JSON).content(body)
    }

    /** Written as JSON escapes, so what reaches the reader is exactly the UTF-16 units named. */
    private static String escaped(List<Integer> units) {
        units.collect { String.format('\\u%04X', it) }.join()
    }

    /** Padded with the whitespace JSON allows after a document, so only its length decides. */
    private static String atTheBound(int largest, String taken) {
        taken + " " * (largest - taken.length())
    }

    private static String oneByteOver(int largest, String taken) {
        atTheBound(largest, taken) + " "
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    /** Void, so the verification is what fails rather than the view a mock's answer would assert as. */
    private void answeredByTheChange(Closure<?> change) {
        change(Mockito.verify(drafts))
        Mockito.verifyNoMoreInteractions(drafts)
        Mockito.verifyNoInteractions(lists)
    }

    def "reads a reference list version to any member, as the library reads it"() {
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

    def "a version saying nothing on choosing is read with no note at all, and holding no term with none"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR), new ReferenceLists.ListView(1, null, []))

        when:
        def answered = sending(get(AT_VERSION))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == [revision: 1, terms: []]
    }

    def "writes the note as sent, saying nothing where it is null, and answers with the version as the change left it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(put(AT_VERSION + "/note"), body))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        answeredByTheChange {
            it.note(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, seen, said)
        }

        where:
        body                                                  || seen | said
        '{"revision":5,"note":"Pick the nearest.\\n\\tAsk."}' || 5    | new ListNote("Pick the nearest.\n\tAsk.")
        '{"note":null,"revision":2}'                          || 2    | null
    }

    def "adds the term as sent after every other, and answers with the version as the change left it"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(post(AT_VERSION + "/terms"),
                '{"revision":5,"term":"Returns","meaning":"  It was sent back.  "}'))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        answeredByTheChange {
            it.add(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 5, new Term("Returns"),
                    new TermMeaning("  It was sent back.  "))
        }
    }

    def "edits the term the address names, its word and what it means together"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(put(AT_VERSION + "/terms/" + termId),
                '{"meaning":"An invoice is wrong.","term":"Invoicing","revision":5}'))

        then:
        answered.response.status == 200

        and:
        answeredByTheChange {
            it.edit(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 5, BILLING,
                    new Term("Invoicing"), new TermMeaning("An invoice is wrong."))
        }

        where:
        termId << [BILLING.toString(), BILLING.toString().toUpperCase(Locale.ROOT)]
    }

    def "removes the term the address names"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(post(AT_TERM + "/removal"), '{"revision":5}'))

        then:
        answered.response.status == 200
        JSON.convertValue(documentOf(answered), Map) == ANSWER

        and:
        answeredByTheChange {
            it.remove(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 5, BILLING)
        }
    }

    def "moves the term the address names one place the way the address names"() {
        given:
        holding(EnumSet.of(GroupRole.OPERATOR))

        when:
        def answered = sending(sent(post(AT_TERM + "/" + segment), '{"revision":5}'))

        then:
        answered.response.status == 200

        and:
        answeredByTheChange {
            it.move(new GroupId(GROUP), new EntryId(ENTRY), new EntryVersionId(VERSION), READER, 5, BILLING, way)
        }

        where:
        segment || way
        "up"    || ReferenceListDrafts.Way.UP
        "down"  || ReferenceListDrafts.Way.DOWN
    }

    /** Whatever the caller holds in the estate, holding nothing here reaches no version of the group's. */
    def "refuses a change by a caller in no role here as no group, never asking the store"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == "GROUP_NOT_IN_VIEW"

        and:
        Mockito.verifyNoInteractions(drafts, lists)

        where:
        request << [get(AT_VERSION),
                    sent(put(AT_VERSION + "/note"), '{"revision":1,"note":null}'),
                    sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing","meaning":"A charge."}'),
                    sent(put(AT_TERM), '{"revision":1,"term":"Billing","meaning":"A charge."}'),
                    sent(post(AT_TERM + "/removal"), '{"revision":1}'),
                    sent(post(AT_TERM + "/up"), '{"revision":1}')]
    }

    /** A version and a term are each named by an identifier this system minted, or name nothing. */
    def "refuses an address naming no version or no term, whichever identifier names nothing, never asking the store"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 404
        documentOf(answered).get("code").asString() == code

        and:
        Mockito.verifyNoInteractions(drafts, lists)

        where:
        request                                                                                                          || code
        get("/api/groups/${GROUP}/reference-lists/not-an-entry/versions/${VERSION}")                                     || "VERSION_NOT_IN_VIEW"
        get("/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/not-a-version")                                      || "VERSION_NOT_IN_VIEW"
        sent(put("/api/groups/${GROUP}/reference-lists/${ENTRY}/versions/not-a-version/note"), '{"revision":1,"note":null}') || "VERSION_NOT_IN_VIEW"
        sent(put(AT_VERSION + "/terms/not-a-term"), '{"revision":1,"term":"Billing","meaning":"A charge."}')             || "TERM_NOT_IN_VIEW"
        sent(post(AT_VERSION + "/terms/0000000a00004000800000000000f31a/removal"), '{"revision":1}')                     || "TERM_NOT_IN_VIEW"
        sent(post(AT_VERSION + "/terms/not-a-term/down"), '{"revision":1}')                                              || "TERM_NOT_IN_VIEW"
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
        Mockito.verifyNoInteractions(drafts, lists)

        where:
        request                                                                                                    || code
        get(AT_VERSION).queryParam("x", "1")                                                                       || "PARAMETER_UNKNOWN"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":null}').queryParam("x", "1")                         || "PARAMETER_UNKNOWN"
        sent(post(AT_TERM + "/up"), '{"revision":1}').queryParam("x", "1")                                         || "PARAMETER_UNKNOWN"
        sent(put(AT_VERSION + "/note"), '{"note":null}')                                                           || "BODY_UNUSABLE"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":3}')                                                 || "BODY_UNUSABLE"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":null,"terms":[]}')                                   || "BODY_UNUSABLE"
        sent(put(AT_VERSION + "/note"), '{"revision":0,"note":null}')                                              || "BODY_UNUSABLE"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":"One.\\r\\nTwo."}')                                  || "PROSE_LINE_BREAK_CRLF"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":"One.\\rTwo."}')                                     || "NOTE_UNUSABLE"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":"\\n\\t"}')                                          || "NOTE_UNUSABLE"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":"One ' + escaped([0x202E]) + '"}')                   || "PROSE_DIRECTION_CONTROL"
        sent(put(AT_VERSION + "/note"), '{"revision":1,"note":"One ' + escaped([0xDB40, 0xDC41]) + '"}')           || "PROSE_TAG_CHARACTER"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing"}')                                       || "BODY_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing","meaning":null}')                        || "BODY_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"term":"Billing","meaning":"A charge.","note":1}')                     || "BODY_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":-1,"term":"Billing","meaning":"A charge."}')                 || "BODY_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing ","meaning":"A charge."}')                 || "TERM_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Bill\\ning","meaning":"A charge."}')              || "TERM_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Bill\\r\\ning","meaning":"A charge."}')           || "TERM_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"' + "i" * 129 + '","meaning":"A charge."}')       || "TERM_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Bill' + escaped([0x2066]) + '","meaning":"x"}')   || "PROSE_DIRECTION_CONTROL"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Bill' + escaped([0xDB40, 0xDC41]) + '","meaning":"x"}') || "PROSE_TAG_CHARACTER"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing ","meaning":"' + escaped([0x202E]) + '"}') || "TERM_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing","meaning":""}')                          || "TERM_MEANING_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing","meaning":"One\\ntwo"}')                 || "TERM_MEANING_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing","meaning":"' + "i" * 513 + '"}')         || "TERM_MEANING_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Billing","meaning":"A' + escaped([0x202A]) + '"}') || "PROSE_DIRECTION_CONTROL"
        sent(put(AT_TERM), '{"revision":1,"term":"Billing"}')                                                      || "BODY_UNUSABLE"
        sent(post(AT_TERM + "/removal"), '{"revision":1,"term":"Billing"}')                                        || "BODY_UNUSABLE"
        sent(post(AT_TERM + "/removal"), '{}')                                                                     || "BODY_UNUSABLE"
        sent(post(AT_TERM + "/down"), '{"revision":"1"}')                                                          || "BODY_UNUSABLE"
        sent(post(AT_TERM + "/down"), '[1]')                                                                       || "BODY_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), '{"revision":1,"term":"Bill' + escaped([0x200B]) + 'ing","meaning":"x"}') || "PROSE_INVISIBLE_CHARACTER"
        sent(put(AT_VERSION + "/note"), oneByteOver(ReferenceListController.LARGEST_NOTE, '{"revision":1,"note":null}')) || "BODY_UNUSABLE"
        sent(post(AT_VERSION + "/terms"), oneByteOver(ReferenceListController.LARGEST_TERM, TAKEN_TERM))           || "BODY_UNUSABLE"
        sent(put(AT_TERM), oneByteOver(ReferenceListController.LARGEST_TERM, TAKEN_TERM))                          || "BODY_UNUSABLE"
        sent(post(AT_TERM + "/removal"), oneByteOver(ReferenceListController.LARGEST_REVISION, '{"revision":1}'))  || "BODY_UNUSABLE"
        sent(post(AT_TERM + "/up"), oneByteOver(ReferenceListController.LARGEST_REVISION, '{"revision":1}'))       || "BODY_UNUSABLE"
    }

    /** At the bound each is taken, so the rows one byte past it are refused for their length alone. */
    def "a body exactly as long as its address reads is taken"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 200

        where:
        request << [sent(put(AT_VERSION + "/note"), atTheBound(ReferenceListController.LARGEST_NOTE, '{"revision":1,"note":null}')),
                    sent(post(AT_VERSION + "/terms"), atTheBound(ReferenceListController.LARGEST_TERM, TAKEN_TERM)),
                    sent(post(AT_TERM + "/removal"), atTheBound(ReferenceListController.LARGEST_REVISION, '{"revision":1}'))]
    }

    /** What the store refused is answered as the store refused it; the version is not read again. */
    def "answers a change the store refuses with that refusal, and reads nothing after it"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))
        willThrow(refusal.raised()).given(drafts).move(new GroupId(GROUP), new EntryId(ENTRY),
                new EntryVersionId(VERSION), READER, 5, BILLING, ReferenceListDrafts.Way.UP)

        when:
        def answered = sending(sent(post(AT_TERM + "/up"), '{"revision":5}'))

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == refusal.code().name()
        Mockito.verifyNoInteractions(lists)

        where:
        refusal                                 || status
        LibraryRefusal.DRAFT_WRITTEN_SINCE_READ || 409
        LibraryRefusal.TERM_AT_END              || 409
        LibraryRefusal.TERM_NOT_IN_VIEW         || 404
        LibraryRefusal.VERSION_STANDING_REFUSES || 409
    }

    /** The reference list kind's own segment, and the ways a term moves each under its own. */
    def "a reference list version and its terms are addressed under the spellings their vocabularies publish"() {
        expect:
        ReferenceListController.VERSION.contains("/" + EntryKind.REFERENCE_LIST.segment() + "/")
        (ReferenceListController.MOVE =~ /\{way:([^}]*)}/)[0][1].split(/\|/) as List ==
                ReferenceListDrafts.Way.values()*.name()*.toLowerCase(Locale.ROOT)
    }
}
