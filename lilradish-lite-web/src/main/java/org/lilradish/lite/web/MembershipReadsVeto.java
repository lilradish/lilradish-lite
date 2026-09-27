package org.lilradish.lite.web;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Refuses to start while a handler asking membership alone answers anything but a read. Every change in a
 * group asks a permission there, so membership guarding one would let every member make it. Judged once,
 * when the mappings exist; a mapping naming no method answers every method and is refused too.
 */
@Component
final class MembershipReadsVeto implements SmartInitializingSingleton {

    private static final Set<RequestMethod> READS = EnumSet.of(RequestMethod.GET, RequestMethod.HEAD);

    private final RequestMappingHandlerMapping mappings;

    MembershipReadsVeto(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapped :
                mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = mapped.getKey().getMethodsCondition().getMethods();
            if (mapped.getValue().getMethodAnnotation(GroupMembershipRequired.class) != null
                    && (methods.isEmpty() || !READS.containsAll(methods))) {
                throw new IllegalStateException(
                        mapped.getValue().getMethod() + " asks membership alone of something other than a read");
            }
        }
    }
}
