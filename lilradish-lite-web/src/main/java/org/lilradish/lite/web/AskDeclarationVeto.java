package org.lilradish.lite.web;

import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Refuses to start over what {@link ActAdmission} would first fail on the request reaching it: a handler under the
 * prefix saying not exactly one thing it asks, or a bean named by an address there, which can say nothing.
 */
@Component
final class AskDeclarationVeto implements SmartInitializingSingleton {

    private final RequestMappingHandlerMapping mappings;

    private final BeanNameUrlHandlerMapping beanNameMappings;

    AskDeclarationVeto(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
            @Qualifier("beanNameHandlerMapping") BeanNameUrlHandlerMapping beanNameMappings) {
        this.mappings = mappings;
        this.beanNameMappings = beanNameMappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapped :
                mappings.getHandlerMethods().entrySet()) {
            boolean underThePrefix =
                    mapped.getKey().getPatternValues().stream().anyMatch(AskDeclarationVeto::mayAnswerUnderThePrefix);
            if (underThePrefix && !ActAdmission.declaresExactlyOne(mapped.getValue())) {
                throw ActAdmission.misdeclared(mapped.getValue());
            }
        }
        for (Map.Entry<String, Object> named : beanNameMappings.getHandlerMap().entrySet()) {
            if (mayAnswerUnderThePrefix(named.getKey())) {
                throw ActAdmission.methodless(named.getValue());
            }
        }
    }

    // A pattern whose first segment is no literal may match the prefix's own at run time.
    private static boolean mayAnswerUnderThePrefix(String pattern) {
        int firstSegmentEnds = pattern.indexOf('/', 1);
        String firstSegment = firstSegmentEnds < 0 ? pattern : pattern.substring(0, firstSegmentEnds);
        return CallerAdmission.answersThisApplication(PathContainer.parsePath(pattern))
                || PathPatternParser.defaultInstance.parse(firstSegment).hasPatternSyntax();
    }
}
