package org.lilradish.lite.web

import static org.mockito.ArgumentMatchers.any
import static org.mockito.BDDMockito.given
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.ServletContext
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import java.sql.SQLException
import org.apache.catalina.connector.ClientAbortException
import org.libprunus.core.error.ApiErrorException
import org.libprunus.core.error.ErrorCategory
import org.libprunus.core.error.ErrorCode
import org.libprunus.spring.error.ApiErrorAutoConfiguration
import org.libprunus.spring.error.ApiErrorHandler
import org.lilradish.lite.app.currency.GroupCurrencies
import org.lilradish.lite.app.estate.EstateRoleGrants
import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.app.groupregister.GroupChanges
import org.lilradish.lite.app.groupregister.GroupRegister
import org.lilradish.lite.app.identification.UserIdentification
import org.lilradish.lite.app.library.EntryChanges
import org.lilradish.lite.app.library.Library
import org.lilradish.lite.app.library.Offers
import org.lilradish.lite.app.library.QuestionDrafts
import org.lilradish.lite.app.library.Questions
import org.lilradish.lite.app.library.ReferenceListDrafts
import org.lilradish.lite.app.library.ReferenceLists
import org.lilradish.lite.app.library.VersionChanges
import org.lilradish.lite.app.library.WorkflowDrafts
import org.lilradish.lite.app.library.Workflows
import org.lilradish.lite.app.measurements.Measurements
import org.lilradish.lite.app.members.Members
import org.lilradish.lite.app.members.MembershipChanges
import org.lilradish.lite.app.people.People
import org.lilradish.lite.app.pool.PoolChanges
import org.lilradish.lite.app.pool.PoolPeople
import org.lilradish.lite.app.run.RunChanges
import org.lilradish.lite.app.run.RunList
import org.lilradish.lite.app.run.RunSteps
import org.lilradish.lite.app.run.Runs
import org.lilradish.lite.app.run.StepActs
import org.lilradish.lite.app.standing.Holdings
import org.lilradish.lite.app.start.StartRuns
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.EmittedNames
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.ApplicationContext
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.StaticMessageSource
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import spock.lang.Specification
import tools.jackson.core.exc.JacksonIOException
import tools.jackson.databind.json.JsonMapper

/**
 * A method an address does not take, asked of every kind of address this application has, every
 * controller loaded. The library's outlet is imported as every slice imports it, so what answers is
 * what replaces it.
 */
@WebMvcTest
@ImportAutoConfiguration(ApiErrorAutoConfiguration)
class RefusalOutletIntegrationSpec extends Specification {

    static final UserId STEWARD = new UserId("000001")

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final String PERSON = "/api/pool/people/00000002-0000-4000-8000-000000000501"

    static final String IN_A_GROUP = "/api/groups/00000003-0000-4000-8000-000000000501"

    static final String MEMBERS = IN_A_GROUP + "/members"

    static final String AN_ENTRY = IN_A_GROUP + "/reference-lists/00000006-0000-4000-8000-000000000501"

    static final String A_VERSION = AN_ENTRY + "/versions/00000007-0000-4000-8000-000000000501"

    static final String A_TERM = A_VERSION + "/terms/00000009-0000-4000-8000-000000000501"

    static final String A_QUESTION_VERSION = IN_A_GROUP +
            "/questions/00000006-0000-4000-8000-000000000502/versions/00000007-0000-4000-8000-000000000502"

    static final String A_WORKFLOW_VERSION = IN_A_GROUP +
            "/workflows/00000006-0000-4000-8000-000000000503/versions/00000007-0000-4000-8000-000000000503"

    static final String A_RUN = IN_A_GROUP + "/runs/00000008-0000-4000-8000-000000000501"

    static final String A_RAISE = A_RUN + "/ceiling-changes/0000000b-0000-4000-8000-000000000501"

    static final String A_STEP = A_RUN + "/steps/00000009-0000-4000-8000-000000000501"

    /** Unavailable as busy is, but no refusal of this application's: it stands for every other code answered 5xx. */
    static final ErrorCode ANOTHER_UNAVAILABLE = new ErrorCode() {
        @Override
        String code() {
            "ANOTHER_UNAVAILABLE"
        }

        @Override
        ErrorCategory category() {
            ErrorCategory.UNAVAILABLE
        }
    }

    static final String AN_EMITTED_FILE = "/" + ClientRouteFallback.HASHED_FILES +
            new ClassPathResource("static/" + ClientRouteFallback.HASHED_FILES).getFile().listFiles()
                    .find { EmittedNames.HASHED.matcher(it.name).matches() }.name

    @Autowired
    private MockMvc mockMvc

    @Autowired
    private ApplicationContext context

    @MockitoBean
    private UserIdentification identification

    @MockitoBean
    private EstateRoleGrants grants

    @MockitoBean
    private GroupRoles groupRoles

    @MockitoBean
    private People people

    @MockitoBean
    private PoolPeople poolPeople

    @MockitoBean
    private PoolChanges changes

    @MockitoBean
    private GroupRegister register

    @MockitoBean
    private GroupChanges groupChanges

    @MockitoBean
    private Holdings holdings

    @MockitoBean
    private Members members

    @MockitoBean
    private MembershipChanges membershipChanges

    @MockitoBean
    private Library library

    @MockitoBean
    private EntryChanges entryChanges

    @MockitoBean
    private VersionChanges versionChanges

    @MockitoBean
    private Measurements measurements

    @MockitoBean
    private Questions questions

    @MockitoBean
    private QuestionDrafts questionDrafts

    @MockitoBean
    private Workflows workflows

    @MockitoBean
    private WorkflowDrafts workflowDrafts

    @MockitoBean
    private ReferenceLists referenceLists

    @MockitoBean
    private ReferenceListDrafts referenceListDrafts

    @MockitoBean
    private GroupCurrencies currencies

    @MockitoBean
    private Runs runs

    @MockitoBean
    private RunChanges runChanges

    @MockitoBean
    private RunList runList

    @MockitoBean
    private Offers offers

    @MockitoBean
    private RunSteps runSteps

    @MockitoBean
    private StepActs stepActs

    @MockitoBean
    private StartRuns startRuns

    def setup() {
        given(identification.identify(any(HttpServletRequest))).willReturn(Optional.of(STEWARD))
    }

    /** As this application's own pages send it, so the door lets a method that is not safe by. */
    private MvcResult sending(String method, String address) {
        mockMvc.perform(request(HttpMethod.valueOf(method), address).header("Sec-Fetch-Site", "same-origin"))
                .andReturn()
    }

    /** A person brought into the pool as this application's own pages send one, the body's stream raising as it is read. */
    private static RequestBuilder bringingInWhileReadingRaises(Exception raised) {
        { ServletContext servletContext ->
            def asked = new MockHttpServletRequest(servletContext, "POST", "/api/pool/people") {
                @Override
                ServletInputStream getInputStream() {
                    throw raised
                }
            }
            asked.contentType = MediaType.APPLICATION_JSON_VALUE
            asked.addHeader("Sec-Fetch-Site", "same-origin")
            asked
        } as RequestBuilder
    }

    private static Set<String> allowedBy(MvcResult answered) {
        answered.response.getHeader("Allow").split(",").collect { it.trim() } as Set
    }

    def "stands in place of the library's outlet rather than beside it"() {
        expect:
        context.getBeansOfType(ApiErrorHandler).values()*.getClass() == [RefusalOutlet]
    }

    /** Handed to the outlet itself, as the door and the turn hand a refusal to the resolver that asks it. */
    def "answers busy asking to be sent again after the longest wait, told once at warn, and every other refusal as the library does"() {
        given:
        def outlet = LoggerFactory.getLogger(RefusalOutlet) as Logger
        def logged = new ListAppender<ILoggingEvent>()
        logged.start()
        outlet.addAppender(logged)
        def response = new MockHttpServletResponse()

        when:
        def answered = context.getBean(RefusalOutlet).handleApiError(
                new ApiErrorException(code, "Said."), new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered.statusCode.value() == status
        (answered.body as ProblemDetail).properties.code == code.code()
        (answered.body as ProblemDetail).detail == "Said."
        answered.headers.getFirst("Retry-After") == sendAgainAfter

        and: "told at the level named, with a trace only where it is a fault, and nothing written by the outlet itself"
        logged.list*.level == told
        logged.list*.throwableProxy.collect { it != null } == traced
        response.contentAsString.isEmpty()

        cleanup:
        outlet.detachAppender(logged)

        where:
        code                             || status | sendAgainAfter | told          | traced
        RefusalCode.SERVICE_BUSY         || 503    | "30"           | [Level.WARN]  | [false]
        ANOTHER_UNAVAILABLE              || 503    | null           | [Level.ERROR] | [true]
        RefusalCode.NOT_SIGNED_IN        || 401    | null           | []            | []
        RefusalCode.BODY_UNUSABLE        || 400    | null           | []            | []
        RefusalCode.BODY_SENT_TOO_SLOWLY || 400    | null           | []            | []
    }

    /** Every other refusal answered 5xx is the library's to render, so busy answers as the library would have. */
    def "answers busy exactly as the library renders it, with only when to send again added: #kept"() {
        given:
        def sentences = new StaticMessageSource()
        if (sentence != null) {
            sentences.addMessage("problemDetail.SERVICE_BUSY", Locale.ENGLISH, sentence)
        }
        LocaleContextHolder.locale = Locale.ENGLISH
        def outlet = new RefusalOutlet()
        outlet.setMessageSource(sentences)
        def library = new ApiErrorHandler()
        library.setMessageSource(sentences)
        def refused = new ApiErrorException(RefusalCode.SERVICE_BUSY, "Said.")

        when:
        def ours = outlet.handleApiError(
                refused, new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()))
        def theirs = library.handleApiError(
                refused, new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse()))

        then:
        ours.statusCode == theirs.statusCode
        ours.body == theirs.body
        (ours.body as ProblemDetail).detail == detail

        and: "the library's headers and Retry-After beside them, nothing else"
        def sendingAgain = HttpHeaders.copyOf(theirs.headers)
        sendingAgain.set("Retry-After", "30")
        ours.headers == sendingAgain
        !theirs.headers.containsHeader("Retry-After")

        cleanup:
        LocaleContextHolder.resetLocaleContext()

        where:
        kept               | sentence                 || detail
        "no sentence kept" | null                     || "Said."
        "a sentence kept"  | "Too busy to take this." || "Too busy to take this."
    }

    /**
     * Raised as the container raises it on reading a body, for a client gone and for anything else; a cause
     * named or worded as a client's going, but not wrapped as the container or the framework wraps one, is a fault.
     */
    def "writes nothing for a client gone away and logs it at debug, and answers anything else a body raised as a fault"() {
        given:
        given(grants.heldBy(STEWARD)).willReturn(EnumSet.of(EstateRole.STEWARD))
        def outlet = LoggerFactory.getLogger(RefusalOutlet) as Logger
        def logged = new ListAppender<ILoggingEvent>()
        logged.start()
        outlet.addAppender(logged)
        outlet.level = Level.DEBUG

        when:
        def answered = mockMvc.perform(bringingInWhileReadingRaises(raised)).andReturn()

        then:
        answered.response.contentAsString.contains('"code":"INTERNAL"') == faulted
        answered.response.contentAsString.isEmpty() == !faulted
        answered.response.status == status
        logged.list*.level == [told]
        (logged.list.first().throwableProxy != null) == faulted

        and: "nobody brought in"
        Mockito.verifyNoInteractions(changes)

        cleanup:
        outlet.level = null
        outlet.detachAppender(logged)

        where:
        raised                                                  || faulted | status | told
        new ClientAbortException(new SocketTimeoutException())  || false   | 200    | Level.DEBUG
        new ClientAbortException(new EOFException())            || false   | 200    | Level.DEBUG
        new IOException("x", new AsyncRequestNotUsableException("Response not usable")) || false | 200 | Level.DEBUG
        new IOException("No space left on device")              || true    | 500    | Level.ERROR
        new CannotCreateTransactionException("x", new SQLException("I/O", new EOFException())) || true | 500 | Level.ERROR
        new EOFException()                                      || true    | 500    | Level.ERROR
        new IOException("Connection reset by peer")             || true    | 500    | Level.ERROR
    }

    /**
     * Raised as the framework raises a JSON answer the stream would not take, handed to the outlet itself: no slice
     * lets a response's own stream raise.
     */
    def "writes nothing for a client gone while an answer was written, and answers anything else unwritable as a fault"() {
        given:
        def outlet = LoggerFactory.getLogger(RefusalOutlet) as Logger
        def logged = new ListAppender<ILoggingEvent>()
        logged.start()
        outlet.addAppender(logged)
        outlet.level = Level.DEBUG
        def response = new MockHttpServletResponse()

        when:
        def answered = context.getBean(RefusalOutlet).handleException(new HttpMessageNotWritableException(
                "Could not write JSON", JacksonIOException.construct(raised)),
                new ServletWebRequest(new MockHttpServletRequest(), response))

        then:
        answered?.statusCode?.value() == status
        (answered?.body as ProblemDetail)?.properties?.code == code
        logged.list*.level == told

        and: "the response left as the container left it"
        response.status == 200
        response.contentAsString.isEmpty()

        cleanup:
        outlet.level = null
        outlet.detachAppender(logged)

        where:
        raised                                                 || status | code                    | told
        new ClientAbortException(new SocketTimeoutException()) || null   | null                    | [Level.DEBUG]
        new IOException("No space left on device")             || 500    | "INTERNAL_SERVER_ERROR" | []
        new IOException("Connection reset by peer")            || 500    | "INTERNAL_SERVER_ERROR" | []
    }

    /** The method is the caller's own token, so whatever is sent the sentence holds none of it. */
    def "refuses a method an address does not take in a sentence naming no method"() {
        when:
        def answered = sending(method, "/api/standing")

        then:
        answered.response.status == 405
        answered.response.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        JSON.readTree(answered.response.contentAsString).get("code").asString() == "METHOD_NOT_ALLOWED"
        JSON.readTree(answered.response.contentAsString).get("detail").asString() ==
                "This address does not take that method."

        and: "the method sent nowhere in the answer, and the handler never run"
        !answered.response.contentAsString.contains(method)
        Mockito.verifyNoInteractions(grants, holdings)

        where:
        method << ["PATCH", "POST", "PUT", "DELETE", "FROBNICATE"]
    }

    def "lists the methods an address takes exactly as asking that address for them lists them"() {
        when:
        def refused = sending("PATCH", address)
        def asked = mockMvc.perform(options(address)).andReturn()

        then:
        refused.response.status == 405
        asked.response.status == 200
        allowedBy(refused) == allowedBy(asked)
        allowedBy(refused).contains("OPTIONS")

        and: "with nothing behind any of them asked anything"
        Mockito.verifyNoInteractions(grants, groupRoles, holdings, people, poolPeople, changes, register, groupChanges,
                members, membershipChanges, library, entryChanges, versionChanges, measurements, questions,
                questionDrafts, workflows, workflowDrafts, referenceLists, referenceListDrafts, currencies, runs,
                runChanges, runSteps, stepActs, startRuns)

        where:
        address << ["/api/standing", "/api/people", "/api/pool/people", PERSON, PERSON + "/estate-roles/steward",
                    "/api/pool/search", "/api/groups", "/api/measurements", MEMBERS, MEMBERS + "/00000002-0000-4000-8000-000000000501",
                    MEMBERS + "/00000002-0000-4000-8000-000000000501/roles/owner", IN_A_GROUP + "/pool/search",
                    IN_A_GROUP + "/currency", IN_A_GROUP + "/questions", AN_ENTRY + "/stop", AN_ENTRY + "/versions",
                    A_VERSION + "/submission", A_VERSION + "/approval", A_VERSION + "/retirement", A_VERSION,
                    A_VERSION + "/note", A_VERSION + "/terms", A_TERM, A_TERM + "/removal", A_TERM + "/up",
                    A_TERM + "/down", A_QUESTION_VERSION,
                    A_QUESTION_VERSION + "/instruction", A_QUESTION_VERSION + "/takes", A_WORKFLOW_VERSION,
                    A_WORKFLOW_VERSION + "/gives", A_WORKFLOW_VERSION + "/steps", A_WORKFLOW_VERSION + "/ceiling",
                    A_WORKFLOW_VERSION + "/help", IN_A_GROUP + "/runs", A_RUN + "/stop", A_RUN + "/steps", A_STEP,
                    A_STEP + "/tries/1",
                    A_STEP + "/tries/1/review", A_STEP + "/tries/1/answer",
                    A_RAISE + "/approval", A_RAISE + "/refusal", A_RAISE + "/withdrawal",
                    "/system/people", AN_EMITTED_FILE, "/index.html", "/"]
    }
}
