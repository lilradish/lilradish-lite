package org.lilradish.lite.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.InputStream;
import java.io.Reader;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartRequest;
import org.springframework.web.multipart.support.MultipartResolutionDelegate;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Refuses to start while any handler has the framework hand it a body, rather than reading it through
 * {@link JsonBody}. Judged once, when the mappings exist, so no request pays for it.
 *
 * <p>Where a mapping takes more than reads, anything bound from parameters counts as a body too, the
 * container parsing a form body into them; there only a path variable, a header or the request itself is
 * handed over.
 */
@Component
final class BoundBodyVeto implements SmartInitializingSingleton {

    private static final Set<RequestMethod> READS = EnumSet.of(RequestMethod.GET, RequestMethod.HEAD);

    private final RequestMappingHandlerMapping mappings;

    BoundBodyVeto(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
        this.mappings = mappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapped :
                mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = mapped.getKey().getMethodsCondition().getMethods();
            boolean readsOnly = !methods.isEmpty() && READS.containsAll(methods);
            for (MethodParameter parameter : mapped.getValue().getMethodParameters()) {
                if (handsOverABody(parameter) || (!readsOnly && mayBindAFormBody(parameter))) {
                    throw new IllegalStateException(mapped.getValue().getMethod()
                            + " is handed what a body holds rather than reading it through JsonBody");
                }
            }
        }
    }

    private static boolean handsOverABody(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return parameter.hasParameterAnnotation(RequestBody.class)
                || parameter.hasParameterAnnotation(RequestPart.class)
                || HttpEntity.class.isAssignableFrom(type)
                || InputStream.class.isAssignableFrom(type)
                || Reader.class.isAssignableFrom(type)
                || MultipartRequest.class.isAssignableFrom(type)
                || MultipartResolutionDelegate.isMultipartArgument(parameter.nestedIfOptional());
    }

    private static boolean mayBindAFormBody(MethodParameter parameter) {
        Class<?> type = parameter.getParameterType();
        return !(parameter.hasParameterAnnotation(PathVariable.class)
                || parameter.hasParameterAnnotation(RequestHeader.class)
                || ServletRequest.class.isAssignableFrom(type)
                || ServletResponse.class.isAssignableFrom(type));
    }
}
