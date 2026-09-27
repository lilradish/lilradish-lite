package org.lilradish.lite.web

import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification

/** Asked of a mapping holding one probe handler at a time, as the dispatcher's own mapping would hold it. */
class MembershipReadsVetoSpec extends Specification {

    static class Probe {

        @GroupMembershipRequired
        void inTheGroup() {}

        @NoActRequired
        void askingNothing() {}
    }

    private static MembershipReadsVeto over(String method, List<RequestMethod> methods) {
        def mappings = new RequestMappingHandlerMapping()
        mappings.registerMapping(RequestMappingInfo.paths("/probe").methods(methods as RequestMethod[]).build(),
                new Probe(), Probe.getDeclaredMethods().find { it.name == method })
        assert mappings.handlerMethods.size() == 1
        new MembershipReadsVeto(mappings)
    }

    def "lets membership alone guard a read"() {
        when:
        over("inTheGroup", methods).afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        methods << [[RequestMethod.GET], [RequestMethod.GET, RequestMethod.HEAD], [RequestMethod.HEAD]]
    }

    /** No method named answers every method, a change among them. */
    def "refuses to start while membership alone guards anything that may change something"() {
        when:
        over("inTheGroup", methods).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains("inTheGroup")

        where:
        methods << [[], [RequestMethod.POST], [RequestMethod.GET, RequestMethod.PUT], [RequestMethod.DELETE],
                    [RequestMethod.PATCH]]
    }

    def "asks nothing of a handler that does not ask membership alone, whatever it answers"() {
        when:
        over("askingNothing", [RequestMethod.POST]).afterSingletonsInstantiated()

        then:
        noExceptionThrown()
    }
}
