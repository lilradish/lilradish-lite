package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.registry.EntryName;
import org.jspecify.annotations.Nullable;
import org.libprunus.spring.error.ApiErrorHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/** The admitted pin's shape refusal, with readable question facts beside its sentence. */
@RestControllerAdvice(basePackageClasses = Library.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class SearchPinOutlet {

    private final ApiErrorHandler outlet;

    SearchPinOutlet(ApiErrorHandler outlet) {
        this.outlet = outlet;
    }

    @ExceptionHandler(SearchPinRefusal.class)
    @Nullable
    ResponseEntity<Object> shapeRefused(SearchPinRefusal refused, WebRequest request) {
        ResponseEntity<Object> answered = outlet.handleApiError(refused, request);
        if (answered == null) {
            return null;
        }
        ProblemDetail problem = (ProblemDetail) requireNonNull(answered.getBody());
        problem.setProperty("searchWay", refused.way());
        EntryName question = refused.question();
        if (question != null) {
            problem.setProperty("questionName", question.value());
            problem.setProperty("questionNumber", refused.number());
        }
        return answered;
    }
}
