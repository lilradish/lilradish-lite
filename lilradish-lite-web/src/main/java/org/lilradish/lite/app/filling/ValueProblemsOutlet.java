package org.lilradish.lite.app.filling;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.spring.error.ApiErrorHandler;
import org.lilradish.lite.domain.filling.FillPath;
import org.lilradish.lite.domain.filling.FillProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Values refused for the fields they fill, with where each named stands, as field names and places among many from
 * the first level down, and why, beside how many were found in all. Scoped by the refusal alone and ordered first,
 * so it is asked before the outlet every other refusal takes, whichever handler raised it.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ValueProblemsOutlet {

    private static final String PROBLEMS = "problems";

    private static final String PROBLEMS_FOUND = "problemsFound";

    private final ApiErrorHandler outlet;

    ValueProblemsOutlet(ApiErrorHandler outlet) {
        this.outlet = outlet;
    }

    /* The outlet decides the status, the code and the sentence; null is a response already sent. */
    @ExceptionHandler(ValueProblemsRefusal.class)
    @Nullable
    ResponseEntity<Object> valuesRefused(ValueProblemsRefusal refused, WebRequest request) {
        ResponseEntity<Object> answered = outlet.handleApiError(refused, request);
        if (answered == null) {
            return null;
        }
        ProblemDetail problem = (ProblemDetail) requireNonNull(answered.getBody());
        problem.setProperty(
                PROBLEMS,
                refused.problems().stream()
                        .map(ValueProblemsOutlet::problemAnswer)
                        .toList());
        problem.setProperty(PROBLEMS_FOUND, refused.found());
        return answered;
    }

    private static ProblemAnswer problemAnswer(FillProblem problem) {
        List<Object> path = problem.path().steps().stream()
                .map(step -> switch (step) {
                    case FillPath.Named named -> (Object) named.name().value();
                    case FillPath.Place place -> (Object) place.index();
                })
                .toList();
        return new ProblemAnswer(path, problem.reason().published());
    }

    /** @param path a field's name, or a place among many counted from nought, at each level from the first */
    record ProblemAnswer(List<Object> path, String reason) {}
}
