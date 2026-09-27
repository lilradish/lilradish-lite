package org.lilradish.lite.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.Part
import org.springframework.http.HttpEntity
import org.springframework.http.RequestEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import spock.lang.Specification

/** Asked of a mapping holding one probe handler at a time, as the dispatcher's own mapping would hold it. */
class BoundBodyVetoSpec extends Specification {

    static final List<String> HANDED_A_BODY = ["bodyAnnotated", "entity", "requestEntity", "partAnnotated", "file",
                                               "files", "part", "optionalFile", "multipartRequest", "stream", "reader"]

    static final List<String> BOUND_FROM_PARAMETERS = ["parameter", "modelAttribute", "command", "plainValue", "map",
                                                       "multiValueMap"]

    static class Form {
        String userId
    }

    static class Probe {

        void bodyAnnotated(@RequestBody String body) {}

        void entity(HttpEntity<String> body) {}

        void requestEntity(RequestEntity<String> body) {}

        void partAnnotated(@RequestPart("part") String part) {}

        void file(MultipartFile file) {}

        void files(List<MultipartFile> files) {}

        void part(Part part) {}

        void optionalFile(Optional<MultipartFile> file) {}

        void multipartRequest(MultipartHttpServletRequest request) {}

        void stream(InputStream body) {}

        void reader(Reader body) {}

        void parameter(@RequestParam("userId") String userId) {}

        void modelAttribute(@ModelAttribute Form form) {}

        void command(Form form) {}

        void plainValue(String userId) {}

        void map(Map<String, String> sent) {}

        void multiValueMap(MultiValueMap<String, String> sent) {}

        void readsTheRequest(HttpServletRequest request, HttpServletResponse response, @PathVariable String subjectId,
                             @RequestHeader("Accept") String accepted) {}
    }

    private static BoundBodyVeto over(String method, List<RequestMethod> methods) {
        def mappings = new RequestMappingHandlerMapping()
        mappings.registerMapping(RequestMappingInfo.paths("/probe").methods(methods as RequestMethod[]).build(),
                new Probe(), Probe.getDeclaredMethods().find { it.name == method })
        assert mappings.handlerMethods.size() == 1
        new BoundBodyVeto(mappings)
    }

    def "refuses to start while a handler is handed a body, even on a mapping that only reads"() {
        given:
        def veto = over(method, [RequestMethod.GET])

        when:
        veto.afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains(method)
        refused.message.endsWith("is handed what a body holds rather than reading it through JsonBody")

        where:
        method << HANDED_A_BODY
    }

    /** A form body is parsed into the parameters wherever more than a read is taken. */
    def "refuses to start while a mapping taking more than reads binds anything from parameters"() {
        given:
        def veto = over(method, methods)

        when:
        veto.afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains(method)

        where:
        [method, methods] << [BOUND_FROM_PARAMETERS, [[RequestMethod.POST], [], [RequestMethod.GET, RequestMethod.PUT]]]
                .combinations()
    }

    def "starts where a mapping that only reads binds its parameters"() {
        given:
        def veto = over(method, methods)

        when:
        veto.afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        [method, methods] << [BOUND_FROM_PARAMETERS, [[RequestMethod.GET], [RequestMethod.GET, RequestMethod.HEAD]]]
                .combinations()
    }

    def "starts where a mapping taking more reads only its path, its headers and the request itself"() {
        given:
        def veto = over("readsTheRequest", methods)

        when:
        veto.afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        methods << [[RequestMethod.POST], [], [RequestMethod.DELETE, RequestMethod.PUT]]
    }
}
