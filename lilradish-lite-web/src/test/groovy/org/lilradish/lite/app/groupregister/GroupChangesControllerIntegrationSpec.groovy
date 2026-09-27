package org.lilradish.lite.app.groupregister

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.core.error.ApiErrorException
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupKey
import org.lilradish.lite.domain.identity.GroupName
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.UserId
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
 * What creating and renaming a group are answered with, over a real dispatcher: the status, the group
 * as the register reads it afterwards, and every refusal a request can meet before the store is asked
 * anything. Every controller of the register is loaded.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling.
 * What a change does to the store is its own spec's question; this one asks what change a request was
 * turned into, and what came of it on the way out. Every request says it came from this application's
 * own pages, which is the door's question and is asked of it elsewhere.
 */
@WebMvcTest([GroupRegisterController, GroupChangesController])
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class GroupChangesControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000701")

    static final UUID PERSON = UUID.fromString("00000002-0000-4000-8000-000000000501")

    static final GroupRegister.GroupRow CREATED = new GroupRegister.GroupRow(
            new GroupId(GROUP), new GroupKey("PAYROLL"), new GroupName("Payroll"), true, 1)

    static final String CREATING = '{"name":"Payroll","key":"payroll","subjectId":"' + PERSON + '"}'

    static final String CREATE_REFUSED =
            "This takes a JSON object holding a group's name, its key, and who in the pool is its first member."

    static final String RENAME_REFUSED = "This takes a JSON object holding a group's new name, and nothing else."

    static final String NAME_REFUSED = "A group's name is one to 128 characters on one line: no space but single plain ones" +
            " between words, none at either end, and something in it that shows."

    static final String KEY_REFUSED = "A group's key is two to sixteen English letters and nothing else."

    static final List<String> ROW_MEMBERS = ["groupId", "key", "name", "canBeAdministered", "memberCount"]

    static final String GRINNING_FACE = Character.toString(0x1F600)

    /** The same code point as JSON writes it escaped: a surrogate pair, each half six characters. */
    static final String ESCAPED_FACE = Character.toString(92) + "uD83D" + Character.toString(92) + "uDE00"

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRegister register

    @MockitoBean
    private GroupChanges changes

    private void arriving(Set<EstateRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held)
    }

    /** As this application's own pages send it, which a browser says of every request it makes. */
    private MvcResult sending(MockHttpServletRequestBuilder request) {
        mockMvc.perform(request.header("Sec-Fetch-Site", "same-origin")).andReturn()
    }

    private MvcResult creating(String body) {
        sending(post("/api/groups").contentType(MediaType.APPLICATION_JSON).content(body))
    }

    private MvcResult renaming(String group, String body) {
        sending(patch("/api/groups/" + group).contentType(MediaType.APPLICATION_JSON).content(body))
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    private static String creatingWith(Map<String, Object> members) {
        JSON.writeValueAsString([name: "Payroll", key: "PAYROLL", subjectId: PERSON.toString()] + members)
    }

    /** The members and values the register's own reading of a group answers with, and no other. */
    private static void assertIsTheRow(JsonNode document, String name) {
        assert membersOf(document) == ROW_MEMBERS
        assert document.get("groupId").asString() == GROUP.toString()
        assert document.get("key").asString() == "PAYROLL"
        assert document.get("name").asString() == name
        assert document.get("canBeAdministered").asBoolean()
        assert document.get("memberCount").asLong() == 1L
    }

    /**
     * Created, carrying the group as the register reads it and no address, no group being read on its
     * own. The key reaches the store in capitals whatever case it was typed in, and the caller handed to
     * the store is the one the door let through.
     */
    def "creates a group, answering with how the register reads it and with no address for it"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.create(new GroupName("Payroll"), new GroupKey("PAYROLL"), new SubjectId(PERSON), READER))
                .willReturn(CREATED)

        when:
        def answered = creating(CREATING)

        then:
        answered.response.status == 201
        assertIsTheRow(documentOf(answered), "Payroll")
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "no address a reader could go on to read the group at"
        answered.response.getHeader("Location") == null

        and:
        storeAskedToCreate(new GroupName("Payroll"), new GroupKey("PAYROLL"))
    }

    /**
     * Spacing in JSON means nothing, a member's order neither, an identifier is read in either case, and
     * the longest name the type takes, every character outside the basic plane and escaped, fits.
     */
    def "reads the group a body describes however the body is spelt"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.create(any(GroupName), any(GroupKey), any(SubjectId), any(UserId))).willReturn(CREATED)

        when:
        def answered = creating(body)

        then:
        answered.response.status == 201
        storeAskedToCreate(new GroupName(name), new GroupKey(key))

        where:
        body                                                                                   || name                | key
        ' {\n "subjectId" : "' + PERSON + '", "key":"TrIaGe",\t"name":"Payroll" } '           || "Payroll"           | "TRIAGE"
        creatingWith(name: "Mi" + Character.toString(0x200C) + "tra")                          || "Mi" + Character.toString(0x200C) + "tra" | "PAYROLL"
        creatingWith(subjectId: PERSON.toString().toUpperCase(Locale.ROOT))                     || "Payroll"           | "PAYROLL"
        '{"name":"' + ESCAPED_FACE * 128 + '","key":"PAYROLL","subjectId":"' + PERSON + '"}'   || GRINNING_FACE * 128 | "PAYROLL"
    }

    /** Refused alike before the store is asked anything, in a sentence fixed whatever was sent. */
    def "refuses a body it will not read as a group to create, never asking the store and repeating nothing sent"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = creating(body)

        then:
        answered.response.status == 400
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == CREATE_REFUSED

        and: "nothing that arrived is handed back"
        !answered.response.contentAsString.contains("Payroll")
        !answered.response.contentAsString.contains(PERSON.toString())

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        body << ["", "null", "[]", '"Payroll"', "{}",
                 JSON.writeValueAsString([name: "Payroll", key: "PAYROLL"]),
                 creatingWith(elsewhere: 1),
                 creatingWith(groupId: GROUP.toString()),
                 creatingWith(name: null),
                 creatingWith(key: 7),
                 creatingWith(subjectId: [PERSON.toString()]),
                 creatingWith(subjectId: "not-an-identifier"),
                 creatingWith(subjectId: "+000002-0000-4000-8000-000000000501"),
                 '{"name":"Payroll","name":"Salaries","key":"PAYROLL","subjectId":"' + PERSON + '"}',
                 CREATING + " {}",
                 CREATING + " " * 4096]
    }

    /** Asked of the name before the key, each under its own code, so a reader is told which to put right. */
    def "refuses a name or a key the group cannot have under that one's own code, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = creating(creatingWith(members))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == code
        documentOf(answered).get("detail").asString() == detail

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        members                                                    || code                  | detail
        [name: ""]                                                 || "GROUP_NAME_UNUSABLE" | NAME_REFUSED
        [name: " Payroll"]                                         || "GROUP_NAME_UNUSABLE" | NAME_REFUSED
        [name: "Pay  roll"]                                        || "GROUP_NAME_UNUSABLE" | NAME_REFUSED
        [name: "Pay" + Character.toString(0x2028) + "roll"]        || "GROUP_NAME_UNUSABLE" | NAME_REFUSED
        [name: Character.toString(0x200B)]                         || "GROUP_NAME_UNUSABLE" | NAME_REFUSED
        [name: "g" * 129]                                          || "GROUP_NAME_UNUSABLE" | NAME_REFUSED
        [name: "g" * 129, key: "P"]                                || "GROUP_NAME_UNUSABLE" | NAME_REFUSED
        [key: "P"]                                                 || "GROUP_KEY_UNUSABLE"  | KEY_REFUSED
        [key: "P" * 17]                                            || "GROUP_KEY_UNUSABLE"  | KEY_REFUSED
        [key: "PAYROLL42"]                                         || "GROUP_KEY_UNUSABLE"  | KEY_REFUSED
        [key: "PAY ROLL"]                                          || "GROUP_KEY_UNUSABLE"  | KEY_REFUSED
        [key: "tr" + Character.toString(0x0131)]                   || "GROUP_KEY_UNUSABLE"  | KEY_REFUSED
    }

    /** The store's refusals are answered as refusals under their own codes, the key's among them. */
    def "answers a refusal the store makes under that refusal's own code, and as no group made"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.create(any(GroupName), any(GroupKey), any(SubjectId), any(UserId)))
                .willThrow(new ApiErrorException(refusal, "Refused."))

        when:
        def answered = creating(CREATING)

        then:
        answered.response.status == status
        documentOf(answered).get("code").asString() == refusal.name()

        and: "neither an address nor any member of a group in the answer"
        answered.response.getHeader("Location") == null
        membersOf(documentOf(answered)).disjoint(ROW_MEMBERS)

        where:
        refusal                          || status
        RefusalCode.GROUP_KEY_TAKEN      || 409
        RefusalCode.GROUP_NAME_TAKEN     || 409
        RefusalCode.PERSON_NOT_IN_POOL   || 400
    }

    /** The name changes and the key does not, and the answer is the group as the register now reads it. */
    def "renames a group, answering with how the register now reads it"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        given(changes.rename(new GroupId(GROUP), new GroupName("Salaries"), READER)).willReturn(
                new GroupRegister.GroupRow(new GroupId(GROUP), new GroupKey("PAYROLL"), new GroupName("Salaries"), true, 1))

        when:
        def answered = renaming(spelt, '{"name":"Salaries"}')

        then:
        answered.response.status == 200
        assertIsTheRow(documentOf(answered), "Salaries")

        and:
        storeAskedToRename(new GroupId(GROUP))

        where:
        spelt << [GROUP.toString(), GROUP.toString().toUpperCase(Locale.ROOT)]
    }

    /** A key is given once, when a group is created: a rename carrying one is refused as carrying a member it does not take. */
    def "refuses a body it will not read as a new name, a key among what it refuses, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = renaming(GROUP.toString(), body)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "BODY_UNUSABLE"
        documentOf(answered).get("detail").asString() == RENAME_REFUSED

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        body << ['{"name":"Salaries","key":"SALARY"}', '{"key":"SALARY"}', "{}", '{"name":null}', '{"name":["Salaries"]}',
                 '{"name":"Salaries","groupId":"' + GROUP + '"}', "", "[]"]
    }

    def "refuses a new name the group cannot have under the name's own code, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = renaming(GROUP.toString(), JSON.writeValueAsString([name: name]))

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "GROUP_NAME_UNUSABLE"
        documentOf(answered).get("detail").asString() == NAME_REFUSED

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        name << ["", "Salaries ", "g" * 129]
    }

    /**
     * An address that is no identifier in its one standard form is never put to the store, and is
     * answered exactly as one the store knows nothing of.
     */
    def "answers every address naming no group alike, whether or not the store was asked"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))
        def unknown = "00000009-0000-4000-8000-000000000009"
        given(changes.rename(any(GroupId), any(GroupName), any(UserId))).willThrow(GroupChanges.notInView())

        when:
        def answers = [unknown, "not-an-identifier", "PAYROLL", GROUP.toString() + "0",
                       "+0000003-0000-4000-8000-000000000701"].collect { address ->
            def answered = renaming(address, '{"name":"Salaries"}')
            [answered.response.status, answered.response.getHeader("Cache-Control"),
             answered.response.contentAsString.replace("/api/groups/" + address, "/api/groups/{groupId}")]
        }

        then:
        answers.toSet().size() == 1
        answers.first()[0] == 404
        JSON.readTree(answers.first()[2] as String).get("code").asString() == "GROUP_NOT_IN_VIEW"

        and: "the store asked only about the one address that was an identifier"
        storeAskedToRename(new GroupId(UUID.fromString(unknown)))
    }

    def "refuses a change sent with a parameter, which no change takes, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(request)

        then:
        answered.response.status == 400
        documentOf(answered).get("code").asString() == "PARAMETER_UNKNOWN"

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        request << [post("/api/groups?key=PAYROLL").contentType(MediaType.APPLICATION_JSON).content(CREATING),
                    patch("/api/groups/" + GROUP + "?name=Salaries").contentType(MediaType.APPLICATION_JSON)
                            .content('{"name":"Salaries"}')]
    }

    def "refuses a body declared as anything but JSON as a type it does not take, never asking the store"() {
        given:
        arriving(EnumSet.of(EstateRole.STEWARD))

        when:
        def answered = sending(post("/api/groups").contentType(MediaType.TEXT_PLAIN).content(CREATING))

        then:
        answered.response.status == 415
        documentOf(answered).get("code").asString() == "UNSUPPORTED_MEDIA_TYPE"

        and:
        Mockito.verifyNoInteractions(changes)
    }

    /** The reader holding only the other role is the case the separation exists for. */
    def "refuses a caller whose roles do not reach the register, before the store is asked anything"() {
        given:
        arriving(held)

        when:
        def answered = sending(request)

        then:
        answered.response.status == 403
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"

        and:
        Mockito.verifyNoInteractions(changes)

        where:
        [held, request] << [[EnumSet.of(EstateRole.WATCHER), EnumSet.noneOf(EstateRole)],
                            [post("/api/groups").contentType(MediaType.APPLICATION_JSON).content(CREATING),
                             patch("/api/groups/" + GROUP).contentType(MediaType.APPLICATION_JSON).content('{"name":"Salaries"}')]]
                .combinations()
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void storeAskedToCreate(GroupName name, GroupKey key) {
        Mockito.verify(changes).create(name, key, new SubjectId(PERSON), READER)
        Mockito.verifyNoMoreInteractions(changes)
    }

    private void storeAskedToRename(GroupId group) {
        Mockito.verify(changes).rename(group, new GroupName("Salaries"), READER)
        Mockito.verifyNoMoreInteractions(changes)
    }
}
