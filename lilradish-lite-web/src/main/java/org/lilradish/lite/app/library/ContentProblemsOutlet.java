package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.spring.error.ApiErrorHandler;
import org.lilradish.lite.domain.registry.ContentPlace;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * A submission refused for its content, with each problem's code, part, the keys of its place and excess beside it,
 * and each pin retired since as {@link RetiredPinsOutlet} names one. Ordered first, as that one is.
 */
@RestControllerAdvice(basePackageClasses = Library.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ContentProblemsOutlet {

    private static final String PROBLEMS = "problems";

    private static final String PINS = "pins";

    private final ApiErrorHandler outlet;

    ContentProblemsOutlet(ApiErrorHandler outlet) {
        this.outlet = outlet;
    }

    /* The outlet decides the status, the code and the sentence; null is a response already sent. */
    @ExceptionHandler(ContentProblemsRefusal.class)
    @Nullable
    ResponseEntity<Object> contentRefused(ContentProblemsRefusal refused, WebRequest request) {
        ResponseEntity<Object> answered = outlet.handleApiError(refused, request);
        if (answered == null) {
            return null;
        }
        ProblemDetail problem = (ProblemDetail) requireNonNull(answered.getBody());
        problem.setProperty(
                PROBLEMS,
                refused.problems().stream()
                        .map(ContentProblemsOutlet::problemAnswer)
                        .toList());
        if (!refused.pins().isEmpty()) {
            problem.setProperty(
                    PINS,
                    refused.pins().stream().map(RetiredPinsOutlet::pinAnswer).toList());
        }
        return answered;
    }

    static ProblemAnswer problemAnswer(ContentProblem problem) {
        String code = problem.code().published();
        String part = problem.place().part().published();
        Long excess = problem.excess();
        return switch (problem.place()) {
            case ContentPlace.Whole ignored -> new ProblemAnswer(code, part, null, null, null, null, null, excess);
            case ContentPlace.AtField field ->
                new ProblemAnswer(code, part, field.fieldId(), null, null, null, null, excess);
            case ContentPlace.AtStep step ->
                new ProblemAnswer(code, part, null, null, step.stepId(), null, null, excess);
            case ContentPlace.AtCase routeCase ->
                new ProblemAnswer(code, part, null, null, routeCase.stepId(), routeCase.caseId(), null, excess);
            case ContentPlace.AtBinding binding ->
                new ProblemAnswer(code, part, null, null, null, null, binding.bindingId(), excess);
            case ContentPlace.AtInput input ->
                new ProblemAnswer(code, part, input.fieldId(), null, input.stepId(), input.caseId(), null, excess);
            case ContentPlace.AtTerm term ->
                new ProblemAnswer(code, part, null, term.termId(), null, null, null, excess);
        };
    }

    /**
     * Each key absent where the place names none: a field alone is one of the version's own or a route's, a field
     * beside a step is an input of what that step, or that case of it, leads to.
     *
     * @param excess how far past its bound a size runs, absent for every problem that is not one of size
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ProblemAnswer(
            String code,
            String part,
            @Nullable UUID fieldId,
            @Nullable UUID termId,
            @Nullable UUID stepId,
            @Nullable UUID caseId,
            @Nullable UUID bindingId,
            @Nullable Long excess) {}
}
