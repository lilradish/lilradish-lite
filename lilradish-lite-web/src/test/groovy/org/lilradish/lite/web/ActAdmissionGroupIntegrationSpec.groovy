package org.lilradish.lite.web

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.web.fixture.GroupProbeController
import org.lilradish.lite.web.fixture.MisdeclaredProbeController
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What a handler asking a permission inside a group, or membership of it, asks of the caller who reached
 * it, decided from the handler's own declaration and the group the address names, over a real dispatcher:
 * what is checked is that the group judged is the one the dispatcher matched the address by, and that the
 * handler is handed that one.
 *
 * <p>The addresses are the probes', for the reason {@link ActAdmissionIntegrationSpec} gives. What a
 * caller holds in a group is the boundary this crosses to a database, so it is replaced.
 */
@WebMvcTest(GroupProbeController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@Import([GroupProbeController, MisdeclaredProbeController])
class ActAdmissionGroupIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-000000000901")

    static final UUID NOBODYS_GROUP = UUID.fromString("00000003-0000-4000-8000-000000000909")

    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    private void holding(Set<GroupRole> roles) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(groupRoles.heldBy(READER, new GroupId(GROUP))).willReturn(roles)
    }

    private MvcResult asking(String address) {
        mockMvc.perform(get(address)).andReturn()
    }

    private static String probeIn(String group, String probe = GroupProbeController.CHANGING_MEMBERSHIP) {
        probe.replace("{groupId}", group)
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void lookedUpOnlyIn(List<UUID> groups) {
        groups.each { Mockito.verify(groupRoles).heldBy(READER, new GroupId(it)) }
        Mockito.verifyNoMoreInteractions(groupRoles)
    }

    /** Every member but the address the refusal was made at, which is what each caller sent. */
    private static Map<String, Object> apartFromWhere(MvcResult answered) {
        JSON.convertValue(documentOf(answered), Map).findAll { it.key != "instance" }
    }

    /**
     * Written over every role, so which roles reach the permission is read off the group's own table: a
     * role added there that bundles it must carry its holder through here too.
     */
    def "lets a member through to a handler asking what a role of theirs there reaches, handing it that group"() {
        given:
        holding(held)

        when:
        def answered = asking(probeIn(GROUP.toString()))

        then:
        answered.response.status == 200
        answered.response.contentAsString == GROUP.toString()

        and: "what they hold in the estate never asked, it counting for nothing inside a group"
        Mockito.verifyNoInteractions(grants)

        where:
        held << GroupRole.reaching(GroupPermission.CHANGE_MEMBERSHIP).collect { EnumSet.of(it) }
    }

    /** Whatever role is held, holding one is the whole of what membership asks. */
    def "lets a member holding any role there through to a handler asking membership alone, handing it that group"() {
        given:
        holding(EnumSet.of(role))

        when:
        def answered = asking(probeIn(GROUP.toString(), GroupProbeController.IN_THE_GROUP))

        then:
        answered.response.status == 200
        answered.response.contentAsString == GROUP.toString()

        and:
        Mockito.verifyNoInteractions(grants)

        where:
        role << GroupRole.values()
    }

    /** Read as the dispatcher decoded it, so a group spelt in capitals is the same group. */
    def "admits a group named in either case as the one group it is"() {
        given:
        holding(EnumSet.of(GroupRole.OWNER))

        when:
        def answered = asking(probeIn(GROUP.toString().toUpperCase(Locale.ROOT)))

        then:
        answered.response.status == 200
        answered.response.contentAsString == GROUP.toString()
    }

    def "refuses a member whose roles there do not reach what the handler asks, and the handler never runs"() {
        given:
        holding(held)

        when:
        def answered = asking(probeIn(GROUP.toString()))

        then:
        answered.response.status == 403
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == "ACT_NOT_PERMITTED"

        and: "and no permission is named, the published spelling being the reader's contract"
        GroupPermission.values().every { !answered.response.contentAsString.contains(it.published()) }

        where:
        held << GroupRole.values().findAll { !GroupRole.reaching(GroupPermission.CHANGE_MEMBERSHIP).contains(it) }
                .collect { EnumSet.of(it) }
    }

    /**
     * Three ways of reaching into no group of one's own, one answer, whatever the caller holds across the
     * estate: told apart, the last would say which groups exist to somebody who may see only their own.
     */
    def "answers an address naming no identifier, a group nobody holds, and a group the caller holds nothing in alike"() {
        given:
        holding(EnumSet.noneOf(GroupRole))

        when:
        def malformed = asking(probeIn("not-a-group", probe))
        def missing = asking(probeIn(NOBODYS_GROUP.toString(), probe))
        def notTheirs = asking(probeIn(GROUP.toString(), probe))

        then:
        [malformed, missing, notTheirs].every { it.response.status == 404 }
        [malformed, missing, notTheirs].every { documentOf(it).get("code").asString() == "GROUP_NOT_IN_VIEW" }

        and: "the three answers the same apart from the address each was sent to"
        apartFromWhere(malformed) == apartFromWhere(notTheirs)
        apartFromWhere(missing) == apartFromWhere(notTheirs)
        documentOf(notTheirs).get("instance").asString() == probeIn(GROUP.toString(), probe)

        and: "each a refusal rather than anything a handler put on the wire"
        [malformed, missing, notTheirs].every {
            it.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        }

        and: "and a malformed identifier never looked up at all"
        lookedUpOnlyIn([NOBODYS_GROUP, GROUP])

        where:
        probe << [GroupProbeController.CHANGING_MEMBERSHIP, GroupProbeController.IN_THE_GROUP]
    }

    /** The gate closing on this application's own omission, as a handler declaring nothing does. */
    def "does not answer at a handler that declares two things it asks, or asks in a group at an address naming none"() {
        given:
        holding(EnumSet.allOf(GroupRole))

        when:
        def answered = asking(address)

        then:
        answered.response.status == 500

        and:
        !answered.response.contentAsString.contains(MisdeclaredProbeController.ANSWERED)
        !answered.response.contentAsString.contains(method)

        where:
        address                                                                                      || method
        MisdeclaredProbeController.DECLARING_TWO.replace("{groupId}", GROUP.toString())              || "declaringTwo"
        MisdeclaredProbeController.MEMBERSHIP_AND_PERMISSION.replace("{groupId}", GROUP.toString())  || "membershipAndPermission"
        MisdeclaredProbeController.IN_NO_GROUP                                                       || "inNoGroup"
        MisdeclaredProbeController.MEMBERSHIP_IN_NO_GROUP                                            || "membershipInNoGroup"
    }
}
