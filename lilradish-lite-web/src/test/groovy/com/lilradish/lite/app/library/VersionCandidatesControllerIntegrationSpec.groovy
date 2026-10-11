package com.lilradish.lite.app.library

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import com.lilradish.lite.app.estate.EstateRoleGrants
import com.lilradish.lite.app.group.GroupRoles
import com.lilradish.lite.app.identification.UserIdentification
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.identity.UserId
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.EntryVersionId
import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

@WebMvcTest(VersionCandidatesController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class VersionCandidatesControllerIntegrationSpec extends Specification {
    static final UUID GROUP = UUID.fromString("00000003-0000-4000-8000-00000000cc01")
    static final UUID VERSION = UUID.fromString("00000007-0000-4000-8000-00000000cc01")
    static final UserId READER = new UserId("00cc01")
    static final JsonMapper JSON = JsonMapper.builder().build()

    @Autowired MockMvc mvc
    @MockitoBean UserIdentification identification
    @MockitoBean EstateRoleGrants grants
    @MockitoBean GroupRoles roles
    @MockitoBean VersionCandidates versions

    private void member(boolean held = true) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(EnumSet.allOf(EstateRole))
        given(roles.heldBy(READER, new GroupId(GROUP)))
                .willReturn(held ? EnumSet.of(GroupRole.OPERATOR) : EnumSet.noneOf(GroupRole))
    }

    def "candidate metadata is read over the real group and kind route, retaining explicit current name #name"() {
        given:
        member()
        def page = new VersionCandidates.Page([
                new VersionCandidates.Candidate("Fictional classification", VERSION, 3, name, "web", false)], true)
        given(versions.candidates(new GroupId(GROUP), EntryKind.QUESTION, "Straße", "web", READER)).willReturn(page)
        when:
        def answer = mvc.perform(get("/api/groups/${GROUP}/questions/versions")
                .param("filter", "Straße").param("searchWay", "web")).andReturn()
        then:
        answer.response.status == 200
        JSON.readValue(answer.response.contentAsString, Map) == [items: [[name: "Fictional classification",
                versionId: VERSION.toString(), number: 3, versionName: name, searchWay: "web", searchMatches: false]], more: true]
        where:
        name << [null, "مرحلة 📦", "Renamed current version"]
    }

    def "one independent reference detail omits fabricated halves and preserves the ordered terms"() {
        given:
        member()
        def pin = new DeclarationAnswers.PinnedAnswer("Categories", VERSION, 1, "retired",
                new DeclarationAnswers.NumberedAnswer(UUID.fromString("00000007-0000-4000-8000-00000000cc02"), 2, null), null)
        given(versions.detail(new GroupId(GROUP), new EntryVersionId(VERSION), READER))
                .willReturn(new VersionCandidates.Detail("reference_list", pin, null, null,
                        [(VERSION): ["later", "urgent"]], null, null))
        when:
        def answer = mvc.perform(get("/api/groups/${GROUP}/versions/${VERSION}")).andReturn()
        then:
        answer.response.status == 200
        def body = JSON.readValue(answer.response.contentAsString, Map)
        body.keySet() == ["kind", "version", "terms"] as Set
        body.version.versionName == null
        body.version.containsKey("versionName")
        body.terms == [(VERSION.toString()): ["later", "urgent"]]
    }

    def "unknown and repeated parameters refuse before reading a candidate page"() {
        given:
        member()
        when:
        def answer = mvc.perform(get("/api/groups/${GROUP}/questions/versions")
                .param(parameter, *values)).andReturn()
        then:
        answer.response.status == 400
        Mockito.verifyNoInteractions(versions)
        where:
        parameter  | values
        "filter"   | ["one", "two"]
        "searchWay"| ["web", "other"]
        "limit"    | ["200"]
        "cursor"   | ["next"]
    }

    def "a nonmember cannot read candidates or one point detail"() {
        given:
        member(false)
        when:
        def answer = mvc.perform(get("/api/groups/${GROUP}/${suffix}")).andReturn()
        then:
        answer.response.status == 404
        Mockito.verifyNoInteractions(versions)
        where:
        suffix << ["questions/versions?filter=x", "versions/${VERSION}"]
    }
}
