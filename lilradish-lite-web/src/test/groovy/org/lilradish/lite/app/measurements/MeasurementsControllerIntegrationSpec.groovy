package org.lilradish.lite.app.measurements

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.testutil.GroupRolesStoodIn
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What a reader of the measurements receives, assembled over a real dispatcher: which members the
 * answer carries, and the status and shape of a refusal. Read as the raw document, because a count
 * sent as a string and one sent as a number are two different contracts.
 *
 * <p>The store is replaced, being the boundary this crosses to a database, and so is who is calling.
 * What the store counts is its own spec's question; this one asks what the counts were turned into on
 * the way out, and who is let as far as asking.
 */
@WebMvcTest(MeasurementsController)
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
@GroupRolesStoodIn
class MeasurementsControllerIntegrationSpec extends Specification {

    static final UserId READER = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final Set<String> PROBLEM_MEMBERS = ["status", "title", "code", "detail", "instance"] as Set

    static final Measurements.Measured MEASURED = new Measurements.Measured(
            [new Measurements.ModelFigures(new ModelName("general"), null, 12, 3, 0, 0, 2),
             new Measurements.ModelFigures(new ModelName("general"), new ModelMode("research"), 0, 0, 7, 4, 1),
             new Measurements.ModelFigures(new ModelName("helper"), null, 0, 0, 0, 0, 0)],
            [new Measurements.SystemFigures(new ModelName("general"), 5, 6, 8),
             new Measurements.SystemFigures(new ModelName("helper"), 0, 0, 0)])

    @Autowired
    private MockMvc mockMvc

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private Measurements measurements

    private void arriving(Set<EstateRole> held) {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(READER))
        given(grants.heldBy(READER)).willReturn(held)
    }

    private MvcResult reading() {
        mockMvc.perform(get("/api/measurements")).andReturn()
    }

    private static JsonNode documentOf(MvcResult answered) {
        JSON.readTree(answered.response.contentAsString)
    }

    private static List<String> membersOf(JsonNode node) {
        node.propertyNames().toList()
    }

    def "answers a watcher with every model's figures and this system's, in the order the store read them"() {
        given:
        arriving(held)
        given(measurements.read()).willReturn(MEASURED)

        when:
        def answered = reading()
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["models", "system"]
        answered.response.getHeader("Cache-Control") == "no-store"
        answered.response.getHeader("Expires") == null

        and: "each model in each mode under the members the reader parses, one run as it is sending mode as null"
        document.get("models").every {
            membersOf(it) == ["model", "mode", "productions", "refusedOnReview", "reviews", "refusing", "didNotFit"]
        }
        document.get("models").collect { [it.get("model").asString(), it.get("mode").isNull()] } ==
                [["general", true], ["general", false], ["helper", true]]
        document.get("models").get(1).get("mode").asString() == "research"
        document.get("models").every { row -> membersOf(row).drop(2).every { row.get(it).isIntegralNumber() } }
        document.get("models").collect { row -> membersOf(row).drop(2).collect { row.get(it).asLong() } } ==
                [[12L, 3L, 0L, 0L, 2L], [0L, 0L, 7L, 4L, 1L], [0L, 0L, 0L, 0L, 0L]]

        and: "each model once for this system, whatever modes it was asked in"
        document.get("system").every { membersOf(it) == ["model", "wentWrong", "neverCameBack", "turnedAway"] }
        document.get("system").collect { it.get("model").asString() } == ["general", "helper"]
        document.get("system").every { row -> membersOf(row).drop(1).every { row.get(it).isIntegralNumber() } }
        document.get("system").collect { row -> membersOf(row).drop(1).collect { row.get(it).asLong() } } ==
                [[5L, 6L, 8L], [0L, 0L, 0L]]

        and: "the store read once"
        storeReadOnce()

        where:
        held << [EnumSet.of(EstateRole.WATCHER), EnumSet.of(EstateRole.WATCHER, EstateRole.STEWARD)]
    }

    /** Nothing counted yet is an answer: two empty tables, and not a refusal or a missing member. */
    def "answers a watcher before anything was ever called with two empty tables"() {
        given:
        arriving(EnumSet.of(EstateRole.WATCHER))
        given(measurements.read()).willReturn(new Measurements.Measured([], []))

        when:
        def answered = reading()
        def document = documentOf(answered)

        then:
        answered.response.status == 200
        membersOf(document) == ["models", "system"]
        document.get("models").isArray() && document.get("models").isEmpty()
        document.get("system").isArray() && document.get("system").isEmpty()
        answered.response.getHeader("Cache-Control") == "no-store"

        and:
        storeReadOnce()
    }

    /**
     * Managing the system's shape grants no look at what it has done, and the refusal names the kind of
     * thing refused, never the act asked nor anything about the caller.
     */
    def "refuses a caller whose roles do not reach the measurements, before the store is asked anything"() {
        given:
        arriving(held)

        when:
        def answered = reading()
        def document = documentOf(answered)

        then:
        answered.response.status == 403
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        membersOf(document) as Set == PROBLEM_MEMBERS
        document.get("code").asString() == "ACT_NOT_PERMITTED"
        document.get("detail").asString() == "This caller may not do that."
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "the act asked of them, and who they are, nowhere in it"
        !answered.response.contentAsString.toLowerCase(Locale.ROOT).contains("read_measurements")
        !answered.response.contentAsString.contains(READER.value())

        and:
        Mockito.verifyNoInteractions(measurements)

        where:
        held << [EnumSet.of(EstateRole.STEWARD), EnumSet.noneOf(EstateRole)]
    }

    def "refuses a caller nobody identified as not signed in, before anything else is asked"() {
        given:
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.empty())

        when:
        def answered = reading()
        def document = documentOf(answered)

        then:
        answered.response.status == 401
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        membersOf(document) as Set == PROBLEM_MEMBERS
        document.get("code").asString() == "NOT_SIGNED_IN"
        answered.response.getHeader("Cache-Control") == "no-store"

        and: "carrying nothing a reader of the measurements would be sent, nor anybody's number, nor the act"
        !answered.response.contentAsString.contains("models")
        !answered.response.contentAsString.contains(READER.value())
        !answered.response.contentAsString.toLowerCase(Locale.ROOT).contains("read_measurements")

        and:
        Mockito.verifyNoInteractions(measurements)
        Mockito.verifyNoInteractions(grants)
    }

    /** A stored name that will not show fails the whole read: accepted, and loud. */
    def "answers measurements the store holds a name this system will not show in as the server's own failure"() {
        given:
        arriving(EnumSet.of(EstateRole.WATCHER))
        given(measurements.read()).willThrow(
                new IllegalStateException("A call names a model under a name this system will not show"))

        when:
        def answered = reading()

        then:
        answered.response.status == 500
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        documentOf(answered).get("code").asString() == "INTERNAL"

        and: "with neither table, nor what the failure said, on the wire"
        !answered.response.contentAsString.contains("models")
        !answered.response.contentAsString.contains("will not show")
    }

    /** Void, so the verification is what fails rather than the null a mock's answer would assert as. */
    private void storeReadOnce() {
        Mockito.verify(measurements).read()
        Mockito.verifyNoMoreInteractions(measurements)
    }
}
