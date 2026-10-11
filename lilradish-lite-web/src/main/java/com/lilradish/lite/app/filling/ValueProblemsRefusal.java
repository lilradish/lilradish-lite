package com.lilradish.lite.app.filling;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.filling.FillProblem;
import com.lilradish.lite.domain.filling.FillProblems;
import java.util.List;
import org.libprunus.core.error.ApiErrorException;

/**
 * Carries refused values' field paths and reasons, plus the total found, never values in its message.
 * {@link ValueProblemsOutlet} includes those paths in the answer wherever this is raised.
 */
public final class ValueProblemsRefusal extends ApiErrorException {

    private static final String SENTENCE = "Each value is written as its field takes it.";

    private final transient List<FillProblem> problems;

    private final int found;

    public ValueProblemsRefusal(FillProblems problems) {
        super(RefusalCode.VALUE_DOES_NOT_FIT, SENTENCE);
        requireNonNull(problems, "ValueProblemsRefusal problems must not be null");
        this.problems = problems.problems();
        this.found = problems.found();
    }

    List<FillProblem> problems() {
        return problems;
    }

    int found() {
        return found;
    }
}
