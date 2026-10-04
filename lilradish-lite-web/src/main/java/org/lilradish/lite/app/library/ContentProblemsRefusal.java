package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.registry.ContentProblem;

/**
 * A version refused at submitting for what its stored content holds, carrying the first listed places,
 * their total count, and every pin retired since beside them, and never any of it in its sentence.
 */
final class ContentProblemsRefusal extends ApiErrorException {

    private final transient ContentProblemReport.Result report;

    private final transient List<RetiredPinsRefusal.RetiredPin> pins;

    ContentProblemsRefusal(ContentProblemReport.Result report, List<RetiredPinsRefusal.RetiredPin> pins) {
        super(
                LibraryRefusal.VERSION_CONTENT_DOES_NOT_HOLD.code(),
                LibraryRefusal.VERSION_CONTENT_DOES_NOT_HOLD.sentence());
        this.report = requireNonNull(report, "ContentProblemsRefusal report must not be null");
        this.pins = List.copyOf(requireNonNull(pins, "ContentProblemsRefusal pins must not be null"));
        if (this.report.total() == 0) {
            throw new IllegalArgumentException("ContentProblemsRefusal names at least one problem");
        }
    }

    List<ContentProblem> problems() {
        return report.problems();
    }

    long problemTotal() {
        return report.total();
    }

    /** None where every pin is in service. */
    List<RetiredPinsRefusal.RetiredPin> pins() {
        return pins;
    }
}
