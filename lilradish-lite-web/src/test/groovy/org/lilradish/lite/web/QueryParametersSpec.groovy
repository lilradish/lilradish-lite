package org.lilradish.lite.web

import jakarta.servlet.http.HttpServletRequest
import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.failure.RefusalCode
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import spock.lang.Specification

/**
 * Read off a request as the container hands it over. Only the container's failure to decode is
 * stood in for: it happens on a socket, where {@code RequestLineIntegrationSpec} sends one.
 */
class QueryParametersSpec extends Specification {

    static final Set<String> TAKEN = ["sort", "filter"] as Set

    static final String UNKNOWN_REFUSED = "This address does not take that parameter."

    static final String SORT_REFUSED = "One sort at a time."

    def "the parameters sent are handed back exactly as sent, every value under every name, where the address takes every name"() {
        given:
        def request = new MockHttpServletRequest(method, "/api/pool/people")
        sent.each { name, values -> request.addParameter(name, values as String[]) }

        when:
        def read = QueryParameters.sent(request, TAKEN, UNKNOWN_REFUSED)

        then:
        read.collectEntries { name, values -> [(name): values.toList()] } == sent

        where:
        [method, sent] << [["GET", "HEAD"], [[:], [sort: ["userId"]], [sort: ["userId", "userId"], filter: [" a,b "]]]]
                .combinations()
    }

    /** The container's parameters include a form body, and reading them drains it. */
    def "reads no parameters for a request that is not a read, leaving its body unread"() {
        given:
        def request = new MockHttpServletRequest(method, "/api/pool/people")
        request.contentType = "application/x-www-form-urlencoded"
        request.content = "sort=userId".bytes
        request.addParameter("sort", "userId")

        when:
        QueryParameters.sent(request, TAKEN, UNKNOWN_REFUSED)

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "Query parameters are read for a read only"

        and:
        request.inputStream.readAllBytes() == "sort=userId".bytes

        where:
        method << ["POST", "PUT", "PATCH", "DELETE", "OPTIONS", "get"]
    }

    /**
     * Refused by name before anything is read by value, so a name this does not take refuses the
     * request whatever else is wrong with it — a name with brackets after it among them, which binding
     * would have read as the name without them.
     */
    def "a parameter the address does not take is refused in the caller's own sentence, whatever else was sent"() {
        given:
        def request = new MockHttpServletRequest("GET", "/api/pool/people")
        sent.each { name, values -> request.addParameter(name, values as String[]) }

        when:
        QueryParameters.sent(request, TAKEN, UNKNOWN_REFUSED)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.PARAMETER_UNKNOWN
        refused.message == UNKNOWN_REFUSED

        where:
        sent << [[srot: ["userId"]], ["sort[]": ["userId"]], [sort: ["userId"], cursor: ["x"]],
                 [sort: ["a", "b"], "page[size]": ["10"]], [Sort: ["userId"]]]
    }

    /**
     * The container's own account of the failure quotes the bytes it could not decode, so the refusal
     * carries no reason at all, and is the caller's to fix rather than a fault of this server's.
     */
    def "a query the container cannot decode is refused as a bad request that says nothing of what was sent"() {
        given:
        def request = Stub(HttpServletRequest) {
            getMethod() >> "GET"
            getParameterMap() >> { throw new IllegalStateException("cannot decode %ZZ") }
        }

        when:
        QueryParameters.sent(request, TAKEN, UNKNOWN_REFUSED)

        then:
        def refused = thrown(ResponseStatusException)
        refused.statusCode == HttpStatus.BAD_REQUEST
        refused.reason == null
        refused.cause == null
    }

    /** Judged off the query string, so a form body is left unread for whoever reads the body. */
    def "an address taking no parameter lets through a request sending none, leaving its body unread"() {
        given:
        def request = new MockHttpServletRequest("POST", "/api/groups")
        request.queryString = query
        request.contentType = "application/x-www-form-urlencoded"
        request.content = "a=1".bytes

        when:
        QueryParameters.requireNone(request, UNKNOWN_REFUSED)

        then:
        noExceptionThrown()
        request.inputStream.readAllBytes() == "a=1".bytes

        where:
        query << [null, ""]
    }

    def "an address taking no parameter refuses any sent, in the caller's own sentence"() {
        given:
        def request = new MockHttpServletRequest(method, "/api/groups")
        request.queryString = query

        when:
        QueryParameters.requireNone(request, UNKNOWN_REFUSED)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.PARAMETER_UNKNOWN
        refused.message == UNKNOWN_REFUSED

        where:
        [method, query] << [["POST", "PATCH"], ["a=1", "&", "sort"]].combinations()
    }

    def "a parameter not sent reads as absent, and one sent once as its one value"() {
        expect:
        QueryParameters.soleValue(sent, "sort", RefusalCode.LIST_SORT_UNUSABLE, SORT_REFUSED) == value

        where:
        sent                                                 || value
        [:]                                                  || null
        [filter: ["userId"] as String[]]                     || null
        [sort: ["-userId"] as String[]]                      || "-userId"
        [sort: [""] as String[]]                             || ""
    }

    def "a parameter sent twice is refused with the caller's code and sentence rather than read as either value"() {
        when:
        QueryParameters.soleValue([sort: ["userId", "-userId"] as String[]], "sort", RefusalCode.LIST_SORT_UNUSABLE,
                SORT_REFUSED)

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.LIST_SORT_UNUSABLE
        refused.message == SORT_REFUSED
    }
}
