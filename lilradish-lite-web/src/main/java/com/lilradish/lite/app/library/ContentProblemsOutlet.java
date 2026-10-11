package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.domain.registry.ContentPlace;
import com.lilradish.lite.domain.registry.ContentProblem;
import java.util.List;
import java.util.UUID;
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
 * Content refused with each problem's code, part, the keys of its place and excess beside it,
 * and each pin retired since as {@link RetiredPinsOutlet} names one. Ordered first, as that one is.
 */
@RestControllerAdvice(basePackageClasses = Library.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ContentProblemsOutlet {

    private static final String PROBLEMS = "problems";

    private static final String PROBLEM_TOTAL = "problemTotal";

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
        problem.setProperty(PROBLEM_TOTAL, refused.problemTotal());
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
        ContentProblem.FileLimits fileLimits = problem.fileLimits();
        return switch (problem.place()) {
            case ContentPlace.Whole ignored ->
                new ProblemAnswer(code, part, null, null, null, null, null, null, null, excess, fileLimits);
            case ContentPlace.AtDrawing drawing ->
                new ProblemAnswer(code, part, null, null, null, null, null, drawing.name(), null, excess, fileLimits);
            case ContentPlace.AtInstruction instruction ->
                new ProblemAnswer(
                        code, part, null, null, null, null, null, instruction.name(), null, excess, fileLimits);
            case ContentPlace.AtField field ->
                new ProblemAnswer(code, part, field.fieldId(), null, null, null, null, null, null, excess, fileLimits);
            case ContentPlace.AtStep step ->
                new ProblemAnswer(code, part, null, null, step.stepId(), null, null, null, null, excess, fileLimits);
            case ContentPlace.AtStepNamed step ->
                new ProblemAnswer(
                        code, part, null, null, step.stepId(), null, null, step.name(), null, excess, fileLimits);
            case ContentPlace.AtStepFields step ->
                new ProblemAnswer(
                        code, part, null, null, step.stepId(), null, null, null, step.fields(), excess, fileLimits);
            case ContentPlace.AtCase routeCase ->
                new ProblemAnswer(
                        code,
                        part,
                        null,
                        null,
                        routeCase.stepId(),
                        routeCase.caseId(),
                        null,
                        null,
                        null,
                        excess,
                        fileLimits);
            case ContentPlace.AtCaseField field ->
                new ProblemAnswer(
                        code,
                        part,
                        field.fieldId(),
                        null,
                        field.stepId(),
                        field.caseId(),
                        null,
                        null,
                        null,
                        excess,
                        fileLimits);
            case ContentPlace.AtBinding binding ->
                new ProblemAnswer(
                        code, part, null, null, null, null, binding.bindingId(), null, null, excess, fileLimits);
            case ContentPlace.AtInput input ->
                new ProblemAnswer(
                        code,
                        part,
                        input.fieldId(),
                        null,
                        input.stepId(),
                        input.caseId(),
                        null,
                        null,
                        null,
                        excess,
                        fileLimits);
            case ContentPlace.AtTerm term ->
                new ProblemAnswer(code, part, null, term.termId(), null, null, null, null, null, excess, fileLimits);
        };
    }

    /**
     * Each key absent where the place names none: a field alone is one of the version's own or a route's, a field
     * beside a step is either an input or a case output, distinguished by its problem code.
     *
     * @param fields fields of what a step runs, beside the step, as what declares them names them, in its order
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
            @Nullable String name,
            @Nullable List<String> fields,
            @Nullable Long excess,
            ContentProblem.@Nullable FileLimits fileLimits) {}
}
