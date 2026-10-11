package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.libprunus.spring.error.ApiErrorHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/** Names stay separate from the English detail, which clients must never parse. */
@RestControllerAdvice(basePackageClasses = Library.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class InputReferenceOutlet {

    private final ApiErrorHandler outlet;

    InputReferenceOutlet(ApiErrorHandler outlet) {
        this.outlet = outlet;
    }

    @ExceptionHandler(InputReferenceRefusal.class)
    @Nullable
    ResponseEntity<Object> referencesRefused(InputReferenceRefusal refused, WebRequest request) {
        ResponseEntity<Object> answered = outlet.handleApiError(refused, request);
        if (answered == null) {
            return null;
        }
        ProblemDetail problem = (ProblemDetail) requireNonNull(answered.getBody());
        problem.setProperty("names", refused.names());
        return answered;
    }
}
