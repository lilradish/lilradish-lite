package org.lilradish.lite.app.pool

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

import jakarta.servlet.http.HttpServletRequest
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.libprunus.core.error.ApiErrorException
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.people.PersonName
import org.lilradish.lite.testutil.GroupRolesStoodIn
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
 * What changes to the pool are answered with, over a real dispatcher: the status, the
 * address a new stay is found at, the person as the pool reads them afterwards, and every refusal a
 * request can meet before the store is asked anything. Every controller of the pool is loaded, so
 * the methods an address is said to take are all the methods it takes.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling.
 * What a change does to the store is its own spec's question; this one asks what change a request was
 * turned into, and what came of it on the way out. Every request says it came from this application's
 * own pages, which is the door's question and is asked of it elsewhere.
 */
@WebMvcTest([PoolPeopleController, PoolChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class PoolChangesControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID PERSON = UUID.fromString("00000002-0000-4000-8000-000000000501")

    static final PoolPeople.PoolPersonPanel PANEL = new PoolPeople.PoolPersonPanel(new SubjectId(PERSON),
            new UserId("000501"), new PersonName("Ada Lovelace"), EnumSet.of(EstateRole.WATCHER), EnumSet.noneOf(EstateRole),
            [new GroupName("Payroll")], false)

    static final String NAMING = '{"userId":"000501"}'

    static final String BODY_REFUSED = "This takes a JSON object naming one user by their user number."

    static final String GRINNING_FACE = Character.toString(0x1F600)

    /** The same code point as JSON writes it escaped: a surrogate pair, each half six characters. */
    static final String ESCAPED_FACE = Character.toString(92) + "uD83D" + Character.toString(92) + "uDE00"

    /** Every shape an address can take that is not an identifier in its one well-formed shape. */
    static final List<String> MALFORMED = ["not-an-identifier", "a-b-4000-8000-c", PERSON.toString() + "0",
                                           "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz", "+000000a-000b-4000-8000-00000000000c"]

    /** Each change by the method and the address it arrives at. */
    static final List<List<String>> CHANGES = [
            ["POST", "/api/pool/people"],
            ["DELETE", "/api/pool/people/" + PERSON],
            ["PUT", "/api/pool/people/" + PERSON + "/estate-roles/watcher"],
            ["DELETE", "/api/pool/people/" + PERSON + "/estate-roles/watcher"],
    ]

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private PoolPeople people

    @MockitoBean
    private PoolChanges changes

    private void arriving(Set<EstateRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held)
    }

    /** As this application's own pages send it, which a browser says of every request it makes. */
    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private MvcResult bringingIn(String contentType, String body) {
        def request = post("/api/pool/people").content(body)
        sending(contentType == null ? request : request.contentType(contentType))
    }

    private static MockHttpServletRequestBuilder requestFor(String method, String address) {
        switch (method) {
            case "POST": return post(address).contentType(MediaType.APPLICATION_JSON).content(NAMING)
            case "PUT": return put(address)
            case "DELETE": return delete(address)
        }
        throw new IllegalArgumentException("No change arrives by " + method)
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    /** The members and values the pool's own reading of a person answers with, and no other, each asserted apart. */
    private static void assertIsThePanel(JsonNode document) {
        assert membersOf(document) ==
                ["subjectId", "userId", "displayName", "estateRoles", "lastGrantingRoles", "groups", "seeded"]
        assert document.get("subjectId").asString() == PERSON.toString()
        assert document.get("userId").asString() == "000501"
        assert document.get("displayName").asString() == "Ada Lovelace"
        assert document.get("estateRoles").collect { it.asString() } == ["watcher"]
        assert document.get("lastGrantingRoles").isEmpty()
        assert document.get("groups").collect { it.asString() } == ["Payroll"]
        assert !document.get("seeded").asBoolean()
    }

    /**
     * Created, so the answer says where the new stay can be read, and carries the person as that
     * address reads them. The caller handed to the store is the one the door let through.
     */
    def "brings somebody into the pool, answering with where they are now read and how"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.bringIn(new UserId("000501"), READER)).willReturn(PANEL)

        when:
        def answered = bringingIn(MediaType.APPLICATION_JSON_VALUE, NAMING)

        then:
        answered.response.status == 201
        answered.response.getHeader("Location") == "/api/pool/people/" + PERSON
        assertIsThePanel(documentOf(answered))
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        storeAskedToBringIn(new UserId("000501"))
    }

    /**
     * A media type is named without regard to case, and may carry a charset; spacing in JSON means
     * nothing. The longest user number the type takes, every character of it outside the basic plane
     * and escaped, fits in a body this reads, and so does a body at the bound itself.
     */
    def "reads the user a body names however the body and its media type are spelt"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.bringIn(any(UserId), any(UserId))).willReturn(PANEL)

        when:
        def answered = bringingIn(contentType, body)

        then:
        answered.response.status == 201
        storeAskedToBringIn(new UserId(named))

        where:
        contentType                         | body                                                  || named
        "application/json;charset=UTF-8"    | NAMING                                                || "000501"
        "APPLICATION/JSON"                  | NAMING                                                || "000501"
        "application/json"                  | ' \n{ "userId" :\t"000501" }\n '                      || "000501"
        "application/json"                  | NAMING + " " * (4096 - NAMING.length())               || "000501"
        "application/json"                  | '{"userId":"' + ESCAPED_FACE * 256 + '"}'             || GRINNING_FACE * 256
    }

    /** Refused before the store is asked anything, in a sentence fixed whatever was declared. */
    def "refuses a body declared as anything but JSON as a type it does not take, repeating nothing declared"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = bringingIn(contentType, NAMING)

        then:
        answered.response.status == 415
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == "UNSUPPORTED_MEDIA_TYPE"
        documentOf(answered).get("detail").asString() == "This takes a body declared as application/json, in UTF-8."
        answered.response.getHeader("Accept") == MediaType.APPLICATION_JSON_VALUE
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "nothing that was declared or sent is handed back"
        !answered.response.contentAsString.contains("00050")
        !answered.response.contentAsString.contains("plain")
        !answered.response.contentAsString.contains("patch")
        !answered.response.contentAsString.contains("1252")
        !answered.response.contentAsString.contains("urlencoded")

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        contentType << [null, "text/plain", "application/xml", "application/merge-patch+json", "not a media type",
                        "application/json;charset=windows-1252", MediaType.APPLICATION_FORM_URLENCODED_VALUE]
    }

    def "refuses a body sent with a content coding as a coding it does not take, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(post("/api/pool/people").contentType(MediaType.APPLICATION_JSON)
                .header("Content-Encoding", "gzip").content(NAMING))

        then:
        answered.response.status == 415
        documentOf(answered).get("code").asString() == "UNSUPPORTED_MEDIA_TYPE"
        documentOf(answered).get("detail").asString() == "This takes a body sent without a content coding."
        answered.response.getHeader("Accept-Encoding") == "identity"

        and: "the media type not named as the fault, and the store never asked"
        answered.response.getHeader("Accept") == null
        Mockito.verifyNoInteractions(changes)
    }

    /**
     * Refused alike before the store is asked anything, in a sentence fixed whatever was sent: no
     * member or value that arrived comes back in the answer.
     */
    def "refuses a body it will not read, never asking the store and repeating nothing that was sent"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = bringingIn(contentType, body)

        then:
        answered.response.status == 400
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == BODY_REFUSED
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "nothing that arrived is handed back"
        !answered.response.contentAsString.contains("00050")
        !answered.response.contentAsString.contains("elsewhere")

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        contentType                   | body
        "application/json"            | ""
        "application/json"            | "null"
        "application/json"            | "[]"
        "application/json"            | '"000501"'
        "application/json"            | "{}"
        "application/json"            | '{"userId":"000501","elsewhere":1}'
        "application/json"            | '{"userid":"000501"}'
        "application/json"            | '{"userId":500501}'
        "application/json"            | '{"userId":null}'
        "application/json"            | '{"userId":["000501"]}'
        "application/json"            | '{"userId":"000501","userId":"000502"}'
        "application/json"            | '{"userId":"000501"} {}'
        "application/json"            | '{"userId":"000501"'
        "application/json"            | '{"userId":" 000501"}'
        "application/json"            | '{"userId":""}'
        "application/json"            | '{"userId":"' + "0" * 257 + '"}'
        "application/json"            | NAMING + " " * (4096 - NAMING.length() + 1)
    }

    /**
     * Decoded as UTF-8 alone. A byte order mark says nothing a JSON body may, and another encoding is
     * refused rather than guessed at — whatever the bytes might have spelt under it.
     */
    def "refuses a body that is not UTF-8 alone, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(post("/api/pool/people").contentType(MediaType.APPLICATION_JSON).content(encoded(encoding)))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == BODY_REFUSED

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        encoding << ["UTF-16LE", "UTF-16BE behind a byte order mark", "UTF-32", "UTF-8 behind a byte order mark",
                     "UTF-8 broken inside the user number", "an overlong two-byte slash", "an overlong three-byte slash",
                     "an encoded surrogate", "a code point past the last"]
    }

    /** Nothing reads a form as parameters on the way in, so it arrives whole and is refused as the body it is. */
    def "refuses a form sent with a change that takes no body, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(requestFor(method, address).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(form))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        !answered.response.contentAsString.contains("%zz")

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        [method, address, form] << CHANGES.findAll { it[0] != "POST" }
                .collectMany { change -> ["&", "a=1", "a=%zz"].collect { change + it } }
    }

    def "takes somebody out of the pool, answering with nothing, there being nobody in it to read"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(delete("/api/pool/people/" + PERSON))

        then:
        answered.response.status == 204
        answered.response.contentAsString == ""
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        Mockito.verify(changes).remove(new SubjectId(PERSON), READER)
        Mockito.verifyNoMoreInteractions(changes)
    }

    /** The role is read by the spelling it is published under, and the person as the pool reads them after. */
    def "grants a role, answering with the person as the pool reads them after"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.grant(new SubjectId(PERSON), role, READER)).willReturn(PANEL)

        when:
        def answered = sending(put(addressOf(PERSON.toString(), role.published())))

        then:
        answered.response.status == 200
        assertIsThePanel(documentOf(answered))
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        storeAskedToGrant(role)

        where:
        role << EstateRole.values()
    }

    def "withdraws a role, answering with the person as the pool reads them after"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.withdraw(new SubjectId(PERSON), role, READER)).willReturn(PANEL)

        when:
        def answered = sending(delete(addressOf(PERSON.toString(), role.published())))

        then:
        answered.response.status == 200
        assertIsThePanel(documentOf(answered))
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        storeAskedToWithdraw(role)

        where:
        role << EstateRole.values()
    }

    /**
     * A role nobody has names nothing at that address, whoever it is asked of and however the person is
     * written: it is read before the person is, and the store is not asked, so no answer here can
     * depend on whom the address names.
     */
    def "answers a role nobody has as an address that names nothing, whoever it is asked of"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answers = ([PERSON.toString(), "00000009-0000-4000-8000-000000000009"] + MALFORMED).collectMany { subject ->
            ["admin", "Steward", "STEWARD", "steward ", "stewards", "keep_pool"].collect { role ->
                def answered = sending(granting ? put(addressOf(subject, role)) : delete(addressOf(subject, role)))
                [answered.response.status, documentOf(answered).get("code").asString(),
                 documentOf(answered).has("detail")]
            }
        }

        then:
        answers.toSet() == [[404, "NOT_FOUND", false]] as Set

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        granting << [true, false]
    }

    /**
     * An address that is no identifier in its one well-formed shape is never put to the store, the
     * shorter spellings the runtime would read as an identifier among them, and is answered exactly as
     * the store answers for somebody not in the pool.
     */
    def "answers every change to an address naming nobody in view alike, asking the store only of an identifier"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        def nobody = new ApiErrorException(RefusalCode.PERSON_NOT_IN_VIEW, "That person is not in view.")
        given(changes.remove(any(SubjectId), any(UserId))).willThrow(nobody)
        given(changes.grant(any(SubjectId), any(EstateRole), any(UserId))).willThrow(nobody)
        given(changes.withdraw(any(SubjectId), any(EstateRole), any(UserId))).willThrow(nobody)

        when:
        def answers = ([PERSON.toString()] + MALFORMED).collect { subject ->
            def address = "/api/pool/people/" + subject + suffix
            def answered = sending(method == "PUT" ? put(address) : delete(address))
            [answered.response.status, answered.response.getHeader("Cache-Control"),
             answered.response.contentAsString.replace(address, "{address}")]
        }

        then:
        answers.toSet().size() == 1
        answers.first()[0] == 404
        JSON.readTree(answers.first()[2] as String).get("code").asString() == "PERSON_NOT_IN_VIEW"

        and: "the store asked about the one address that was an identifier, and about nothing else"
        storeAskedOnlyAbout(method, suffix)

        where:
        method   | suffix
        "DELETE" | ""
        "PUT"    | "/estate-roles/watcher"
        "DELETE" | "/estate-roles/watcher"
    }

    /** No change takes a parameter, so one sent is refused rather than read as honoured. */
    def "refuses a parameter sent with any change, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(requestFor(method, address).queryParam("dryRun", "true"))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "PARAMETER_UNKNOWN"
        documentOf(answered).get("detail").asString() == "This change takes no parameter."
        !answered.response.contentAsString.contains("dryRun")

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        [method, address] << CHANGES
    }

    /** Only bringing somebody in takes a body, so one sent with any other change is refused, not ignored. */
    def "refuses a body sent with a change that takes none, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(requestFor(method, address).contentType(MediaType.APPLICATION_JSON).content(NAMING))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == "This change takes no body."

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        [method, address] << CHANGES.findAll { it[0] != "POST" }
    }

    /** What the store refuses reaches the reader under the code it was refused with, and its status. */
    def "answers a change the store refuses with the refusal's own code and the status its category decides"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.bringIn(any(UserId), any(UserId))).willThrow(new ApiErrorException(refusal, "Refused."))
        given(changes.remove(any(SubjectId), any(UserId))).willThrow(new ApiErrorException(refusal, "Refused."))
        given(changes.withdraw(any(SubjectId), any(EstateRole), any(UserId))).willThrow(new ApiErrorException(refusal, "Refused."))

        when:
        def answered = sending(requestFor(method, address))

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == refusal.code()
        answered.response.getHeader("Cache-Control") == "no-store"

        where:
        method   | address                                                  | refusal                                || status
        "POST"   | "/api/pool/people"                                       | RefusalCode.USER_NOT_IN_DIRECTORY      || 400
        "POST"   | "/api/pool/people"                                       | RefusalCode.PERSON_ALREADY_IN_POOL     || 409
        "DELETE" | "/api/pool/people/" + PERSON                             | RefusalCode.PERSON_HOLDS_ESTATE_ROLES  || 409
        "DELETE" | "/api/pool/people/" + PERSON                             | RefusalCode.PERSON_IN_GROUPS           || 409
        "DELETE" | "/api/pool/people/" + PERSON + "/estate-roles/steward"   | RefusalCode.LAST_ESTATE_ROLE_GRANTOR   || 409
    }

    /** Reading what the estate measures reaches no change to the pool, and holding nothing reaches none. */
    def "refuses a caller whose roles do not reach a change, before the store is asked anything"() {
        given:
        arriving(held)

        when:
        def answered = sending(requestFor(method, address))

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        [held, change] << [[EnumSet.of(EstateRole.WATCHER), EnumSet.noneOf(EstateRole)], CHANGES].combinations()
        method = change[0]
        address = change[1]
    }

    /** Asking which methods an address takes is answered truthfully, from every mapping at that address. */
    def "answers OPTIONS at every address with each method it takes, touching nothing"() {
        given:
        arriving(EnumSet.of(EstateRole.WATCHER))

        when:
        def answered = mockMvc.perform(options(address)).andReturn()

        then:
        answered.response.status == 200
        answered.response.getHeader("Allow").split(",").collect { it.trim() } as Set == allowed as Set

        and:
        Mockito.verifyNoInteractions(changes)
        Mockito.verifyNoInteractions(people)

        where:
        address                                                  || allowed
        "/api/pool/people"                                       || ["GET", "HEAD", "POST", "OPTIONS"]
        "/api/pool/people/" + PERSON                             || ["GET", "HEAD", "DELETE", "OPTIONS"]
        "/api/pool/people/" + PERSON + "/estate-roles/steward"   || ["PUT", "DELETE", "OPTIONS"]
    }

    private static String addressOf(String subject, String role) {
        "/api/pool/people/" + subject + "/estate-roles/" + role
    }

    private static byte[] encoded(String encoding) {
        switch (encoding) {
            case "UTF-16LE": return NAMING.getBytes(StandardCharsets.UTF_16LE)
            case "UTF-16BE behind a byte order mark": return joined(bytes(0xFE, 0xFF), NAMING.getBytes(StandardCharsets.UTF_16BE))
            case "UTF-32": return NAMING.getBytes(Charset.forName("UTF-32"))
            case "UTF-8 behind a byte order mark": return joined(bytes(0xEF, 0xBB, 0xBF), NAMING.getBytes(StandardCharsets.UTF_8))
            case "UTF-8 broken inside the user number": return inTheUserNumber(bytes(0xC3, 0x28))
            case "an overlong two-byte slash": return inTheUserNumber(bytes(0xC0, 0xAF))
            case "an overlong three-byte slash": return inTheUserNumber(bytes(0xE0, 0x80, 0xAF))
            case "an encoded surrogate": return inTheUserNumber(bytes(0xED, 0xA0, 0x80))
            case "a code point past the last": return inTheUserNumber(bytes(0xF4, 0x90, 0x80, 0x80))
        }
        throw new IllegalArgumentException("No encoding is called " + encoding)
    }

    private static byte[] inTheUserNumber(byte[] sequence) {
        joined('{"userId":"000'.getBytes(StandardCharsets.UTF_8), sequence, '501"}'.getBytes(StandardCharsets.UTF_8))
    }

    private static byte[] bytes(int... values) {
        values.collect { (byte) it } as byte[]
    }

    private static byte[] joined(byte[]... parts) {
        def joined = new ByteArrayOutputStream()
        parts.each { joined.write(it) }
        joined.toByteArray()
    }

    private void storeAskedOnlyAbout(String method, String suffix) {
        if (suffix.isEmpty()) {
            Mockito.verify(changes).remove(new SubjectId(PERSON), READER)
        } else if (method == "PUT") {
            Mockito.verify(changes).grant(new SubjectId(PERSON), EstateRole.WATCHER, READER)
        } else {
            Mockito.verify(changes).withdraw(new SubjectId(PERSON), EstateRole.WATCHER, READER)
        }
        Mockito.verifyNoMoreInteractions(changes)
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void storeAskedToBringIn(UserId user) {
        Mockito.verify(changes).bringIn(user, READER)
        Mockito.verifyNoMoreInteractions(changes)
    }

    private void storeAskedToGrant(EstateRole role) {
        Mockito.verify(changes).grant(new SubjectId(PERSON), role, READER)
        Mockito.verifyNoMoreInteractions(changes)
    }

    private void storeAskedToWithdraw(EstateRole role) {
        Mockito.verify(changes).withdraw(new SubjectId(PERSON), role, READER)
        Mockito.verifyNoMoreInteractions(changes)
    }
}
