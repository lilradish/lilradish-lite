package org.lilradish.lite.app.run;

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

/**
 * A code step refused an answer here for giving otherwise, with the field that refuses it and, where what reads it is
 * why, what reads it, as a try of it gone wrong for that names them. Scoped by the refusal alone and ordered first,
 * so it is asked before the outlet every other refusal takes, whichever handler raised it.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class GivesOtherwiseOutlet {

    private static final String READ_BY = "readBy";

    private static final String FIELD = "field";

    private final ApiErrorHandler outlet;

    GivesOtherwiseOutlet(ApiErrorHandler outlet) {
        this.outlet = outlet;
    }

    /* The outlet decides the status, the code and the sentence; null is a response already sent. */
    @ExceptionHandler(GivesOtherwiseRefusal.class)
    @Nullable
    ResponseEntity<Object> givesOtherwise(GivesOtherwiseRefusal refused, WebRequest request) {
        StepAnswers.GivesOtherwiseNames named = StepAnswers.GivesOtherwiseNames.of(refused.fault());
        ResponseEntity<Object> answered = outlet.handleApiError(refused, request);
        if (answered == null) {
            return null;
        }
        ProblemDetail problem = (ProblemDetail) requireNonNull(answered.getBody());
        StepAnswers.ReadByAnswer readBy = named.readBy();
        if (readBy != null) {
            problem.setProperty(READ_BY, readBy);
        }
        problem.setProperty(FIELD, named.field());
        return answered;
    }
}
