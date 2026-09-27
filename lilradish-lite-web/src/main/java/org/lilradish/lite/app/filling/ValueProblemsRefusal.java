package org.lilradish.lite.app.filling;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.filling.FillProblem;
import org.lilradish.lite.domain.filling.FillProblems;

/**
 * Values refused for the fields they fill, carrying where each named that does not fit stands and why, how many
 * were found in all, and never any value in its sentence. Answered with its places by {@link ValueProblemsOutlet},
 * wherever it is raised.
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
