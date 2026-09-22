package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.workflow.StepId;

/**
 * Which attempt of a step is the one downstream reads: the attempt a person settled, or failing
 * that the last successful one.
 *
 * <p>Kept apart from the query that reads the rows, because a rule expressed in SQL can only be
 * tested against a database. The trade is that eligibility is decided twice, and only one direction
 * is safe: a query may narrow no further than {@code isAnswerable()} does.
 */
public final class AttemptSelection {

    private AttemptSelection() {}

    /**
     * Attempts of one run, in whatever order the query returned them. Two runs together are refused
     * rather than resolved: keyed by step alone they would merge into an answer that reads as
     * though one run produced it.
     *
     * <p>Lookups are by step, so iteration order is a readability matter.
     */
    public static Map<StepId, Candidate> resolve(List<Candidate> attempts) {
        requireNonNull(attempts, "AttemptSelection attempts must not be null");
        Map<StepId, Candidate> chosen = new LinkedHashMap<>();
        RunId run = null;
        for (Candidate candidate : attempts) {
            requireNonNull(candidate, "AttemptSelection attempts must not hold a null attempt");
            if (run == null) {
                run = candidate.runId();
            } else if (!run.equals(candidate.runId())) {
                throw new IllegalArgumentException(
                        "AttemptSelection resolves one run at a time, not " + run + " beside " + candidate.runId());
            }
            if (candidate.isAnswerable()) {
                chosen.merge(candidate.stepId(), candidate, AttemptSelection::preferred);
            }
        }
        return Collections.unmodifiableMap(chosen);
    }

    /**
     * A person's decision beats a later attempt; a model's does not, because an automated review is
     * material beside the attempt and not a decision on it. Between two of a kind the later wins.
     */
    private static Candidate preferred(Candidate held, Candidate candidate) {
        if (held.settledByPerson() != candidate.settledByPerson()) {
            return held.settledByPerson() ? held : candidate;
        }
        return candidate.attempt() > held.attempt() ? candidate : held;
    }

    /**
     * An attempt as the step after it reads one: what it produced, and just enough to say whether
     * it is the answer. Narrower than what a reviewer is shown, deliberately: this is on the path
     * every advance of a run takes. The output is here because the next step binds its arguments
     * from it — inference content the engine cannot do without. A judge's rationale is the
     * inference content it can, and so is not.
     *
     * <p>An attempt that can be a step's answer must have produced one.
     */
    public record Candidate(
            RunId runId,
            StepId stepId,
            int attempt,
            StepStatus status,
            boolean isSuperseded,
            @Nullable ApprovalKind approvedAs,
            @Nullable OutputFields output) {

        public Candidate {
            requireNonNull(runId, "Candidate runId must not be null");
            requireNonNull(stepId, "Candidate stepId must not be null");
            requireNonNull(status, "Candidate status must not be null");
            if (attempt < 1) {
                throw new IllegalArgumentException("Candidate attempt must be positive: " + attempt);
            }
            // Read from the parameters, not isAnswerable(): no component is assigned yet here.
            if (answerable(status, isSuperseded) && output == null) {
                throw new IllegalArgumentException("Candidate can be a step's answer yet produced no output");
            }
        }

        boolean isAnswerable() {
            return answerable(status, isSuperseded);
        }

        /** A superseded attempt is history, and one that never completed produced nothing to read. */
        private static boolean answerable(StepStatus status, boolean isSuperseded) {
            return status == StepStatus.COMPLETED && !isSuperseded;
        }

        boolean settledByPerson() {
            return approvedAs != null && approvedAs.isPersonsDecision();
        }
    }
}
