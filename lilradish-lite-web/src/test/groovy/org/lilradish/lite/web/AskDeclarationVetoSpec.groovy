package org.lilradish.lite.web

import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification

/** Asked of mappings holding one probe handler at a time, as the dispatcher's own mappings would hold it. */
class AskDeclarationVetoSpec extends Specification {

    static class Probe {

        @NoActRequired
        void askingNothing() {}

        void declaringNothing() {}

        @NoActRequired
        @GroupMembershipRequired
        void declaringTwo() {}
    }

    private static AskDeclarationVeto over(List<String> patterns, String method) {
        def mappings = new RequestMappingHandlerMapping()
        mappings.registerMapping(RequestMappingInfo.paths(patterns as String[]).build(),
                new Probe(), Probe.getDeclaredMethods().find { it.name == method })
        assert mappings.handlerMethods.size() == 1
        new AskDeclarationVeto(mappings, new BeanNameUrlHandlerMapping())
    }

    private static AskDeclarationVeto overBeanNamed(String address, Object handler) {
        def beanNameMappings = new BeanNameUrlHandlerMapping()
        beanNameMappings.registerHandler(address, handler)
        assert beanNameMappings.handlerMap.keySet() == [address] as Set
        new AskDeclarationVeto(new RequestMappingHandlerMapping(), beanNameMappings)
    }

    def "lets a handler under the prefix that declares exactly one thing it asks start"() {
        when:
        over(["/api/probe"], "askingNothing").afterSingletonsInstantiated()

        then:
        noExceptionThrown()
    }

    /**
     * A mapping answering on both sides of the prefix is judged by the side it is guarded on, and one whose
     * first segment is no literal by the prefix it may match at run time.
     */
    def "refuses to start while a handler under the prefix declares none, or more than one, of what it asks"() {
        when:
        over(patterns, method).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains(method)
        refused.message.contains("declares not exactly one of the act it asks")

        where:
        patterns                           | method
        ["/api/probe"]                     | "declaringNothing"
        ["/api/probe"]                     | "declaringTwo"
        ["/api"]                           | "declaringNothing"
        ["/api/groups/{groupId}/probe"]    | "declaringTwo"
        ["/probe", "/api/probe"]           | "declaringNothing"
        ["/{segment}/probe"]               | "declaringNothing"
        ["/{prefix:api}/probe"]            | "declaringTwo"
        ["/*/probe"]                       | "declaringNothing"
        ["/ap?/probe"]                     | "declaringNothing"
        ["/**"]                            | "declaringTwo"
    }

    def "asks nothing of a handler answering outside the prefix, whatever it declares"() {
        when:
        over(patterns, method).afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        patterns          | method
        ["/probe"]        | "declaringNothing"
        ["/apis/probe"]   | "declaringTwo"
        ["/"]             | "declaringNothing"
        ["/probe/{any}"]  | "declaringNothing"
        ["/probe/**"]     | "declaringTwo"
    }

    def "refuses to start while a bean is named by an address under the prefix, naming the bean"() {
        given:
        def handler = new Probe()

        when:
        overBeanNamed(address, handler).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "${handler} answers under the prefix with no handler method to declare what it asks on"

        where:
        address << ["/api/probe", "/api", "/api/**", "/ap?/probe", "/*/probe"]
    }

    def "asks nothing of a bean named by an address outside the prefix"() {
        when:
        overBeanNamed(address, new Probe()).afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        address << ["/probe", "/apis/probe", "/probe/*"]
    }
}
