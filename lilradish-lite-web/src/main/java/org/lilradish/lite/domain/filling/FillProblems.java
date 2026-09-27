package org.lilradish.lite.domain.filling;

import static java.util.Objects.requireNonNull;

import java.util.List;

/**
 * What does not fit, in the order the fields are declared, depth first and each field before what it holds, and
 * one reason at each place: the first found there. Only the first {@link Filling} names are held, and how many
 * were found in all, named or not, beside them.
 */
public record FillProblems(List<FillProblem> problems, int found) implements FillOutcome {

    public FillProblems {
        problems = List.copyOf(requireNonNull(problems, "FillProblems problems must not be null"));
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("FillProblems names at least one problem");
        }
        if (found < problems.size()) {
            throw new IllegalArgumentException("FillProblems found no fewer than it names: " + found);
        }
    }
}
