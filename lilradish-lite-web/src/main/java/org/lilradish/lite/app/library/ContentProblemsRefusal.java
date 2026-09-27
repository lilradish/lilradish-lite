package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.registry.ContentProblem;

/**
 * A version refused at submitting for what its stored content holds, carrying every place that does not
 * hold, and every pin retired since beside them, and never any of it in its sentence.
 */
final class ContentProblemsRefusal extends ApiErrorException {

    private final transient List<ContentProblem> problems;

    private final transient List<RetiredPinsRefusal.RetiredPin> pins;

    ContentProblemsRefusal(List<ContentProblem> problems, List<RetiredPinsRefusal.RetiredPin> pins) {
        super(
                LibraryRefusal.VERSION_CONTENT_DOES_NOT_HOLD.code(),
                LibraryRefusal.VERSION_CONTENT_DOES_NOT_HOLD.sentence());
        this.problems = List.copyOf(requireNonNull(problems, "ContentProblemsRefusal problems must not be null"));
        this.pins = List.copyOf(requireNonNull(pins, "ContentProblemsRefusal pins must not be null"));
        if (this.problems.isEmpty()) {
            throw new IllegalArgumentException("ContentProblemsRefusal names at least one problem");
        }
    }

    List<ContentProblem> problems() {
        return problems;
    }

    /** None where every pin is in service. */
    List<RetiredPinsRefusal.RetiredPin> pins() {
        return pins;
    }
}
