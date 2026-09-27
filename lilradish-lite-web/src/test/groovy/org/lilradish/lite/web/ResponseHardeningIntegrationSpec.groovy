package org.lilradish.lite.web

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.standing.Holdings
import org.lilradish.lite.app.standing.StandingController
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.EmittedNames
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification

/**
 * Every kind of answer this application gives, asked of a real dispatcher behind this application's
 * real filters: the document and the files it loads, an address only the loaded page routes, an
 * answer under the prefix, and each way a request there is refused — by the door before anybody is
 * identified or once they are, by the dispatcher, and by the handling behind it.
 *
 * <p>A refusal is where the order among filters shows. Written by the door, it carries these only if
 * the filter setting them ran first.
 */
@WebMvcTest(StandingController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class ResponseHardeningIntegrationSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final Map<String, List<String>> HARDENED = ["X-Content-Type-Options" : ["nosniff"],
                                                       "X-Frame-Options"        : ["DENY"],
                                                       "Content-Security-Policy": ["frame-ancestors 'none'"]]

    static final String AN_EMITTED_FILE = "/" + ClientRouteFallback.HASHED_FILES +
            new ClassPathResource("static/" + ClientRouteFallback.HASHED_FILES).getFile().listFiles()
                    .find { EmittedNames.HASHED.matcher(it.name).matches() }.name

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private Holdings holdings

    private MvcResult sending(String method, String address, Map<String, String> headers) {
        def sent = request(HttpMethod.valueOf(method), address).accept(MediaType.TEXT_HTML, MediaType.ALL)
        headers.each { name, value -> sent = sent.header(name, value) }
        mockMvc.perform(sent).andReturn()
    }

    private static Map<String, List<String>> hardeningOn(MvcResult answered) {
        HARDENED.keySet().collectEntries { [(it): answered.response.getHeaders(it)] }
    }

    def "every answer says what it is and that no page may frame it, whoever answered it and however"() {
        given:
        given(identification.identify(any(HttpServletRequest)))
                .willReturn(identified ? Optional.of(STEWARD) : Optional.empty())

        when:
        def answered = sending(method, address, headers)

        then:
        answered.response.status == status

        and: "each said once, with the policy saying nothing but that"
        hardeningOn(answered) == HARDENED

        where:
        method  | address                                   | identified | headers                               || status
        "GET"   | "/"                                       | false      | [:]                                   || 200
        "GET"   | "/system/people"                          | false      | [:]                                   || 200
        "GET"   | AN_EMITTED_FILE                           | false      | [:]                                   || 200
        "HEAD"  | AN_EMITTED_FILE                           | false      | [:]                                   || 200
        "GET"   | "/assets/index-THERE-IS-NO-SUCH-BUILD.js" | false      | [:]                                   || 404
        "GET"   | "/api/standing"                           | true       | [:]                                   || 200
        "GET"   | "/api/standing"                           | false      | [:]                                   || 401
        "PATCH" | "/api/standing"                           | true       | [:]                                   || 403
        "PATCH" | "/api/standing"                           | true       | ["Sec-Fetch-Site": "same-origin"]     || 405
        "GET"   | "/api/there-is-no-such"                   | true       | [:]                                   || 404
    }
}
