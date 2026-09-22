package org.lilradish.lite.domain.approval;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.inference.Confidence;
import org.lilradish.lite.domain.registry.EntryReference;
import org.lilradish.lite.domain.run.ApprovalKind;
import org.lilradish.lite.domain.run.BlockedCheck;
import org.lilradish.lite.domain.run.InputFields;
import org.lilradish.lite.domain.run.OutputFields;
import org.lilradish.lite.domain.run.Replacement;
import org.lilradish.lite.domain.run.Reversal;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.StepStatus;
import org.lilradish.lite.domain.run.Supersession;
import org.lilradish.lite.domain.workflow.StepId;

/**
 * One attempt as the row holds it, before any view is applied — everything the attempt itself
 * carries, and nothing the run around it decides.
 *
 * <p>What identifies it is run, step and attempt together. A step id is unique only inside one
 * workflow definition, so without the run two executions of one workflow yield attempts that no
 * collection can tell apart.
 *
 * <p>An attempt is decided one way or not at all, and a blocked one names what stopped it: a check,
 * a person's refusal, or a judge's reject — and never a check and a person both, since an output a
 * check refused is one no person ever saw. A judge's reject stands beside either of them, being
 * material beside the attempt rather than a decision on it.
 *
 * <p>Stricter than the database in four places, each needing a check in a baseline before the two
 * agree.
 *
 * @param referenced the entries the step read, so a reviewer can open what an answer was built from
 * @param blockedBy the check that stopped it, absent where a person did
 */
public record ReviewAttempt(
        RunId runId,
        StepId stepId,
        int attempt,
        StepStatus status,
        Reversal reversal,
        InputFields input,
        OutputFields output,
        List<EntryReference> referenced,
        @Nullable BlockedCheck blockedBy,
        @Nullable Confidence confidence,
        @Nullable Approval approval,
        @Nullable Rejection rejection,
        @Nullable Replacement replacement,
        @Nullable Supersession superseded,
        @Nullable Judgement judgement) {

    public ReviewAttempt {
        requireNonNull(runId, "ReviewAttempt runId must not be null");
        requireNonNull(stepId, "ReviewAttempt stepId must not be null");
        requireNonNull(status, "ReviewAttempt status must not be null");
        requireNonNull(reversal, "ReviewAttempt reversal must not be null");
        requireNonNull(input, "ReviewAttempt input must not be null");
        requireNonNull(output, "ReviewAttempt output must not be null");
        requireNonNull(referenced, "ReviewAttempt referenced must not be null");
        if (attempt < 1) {
            throw new IllegalArgumentException("ReviewAttempt attempt must be positive: " + attempt);
        }
        if (approval != null && rejection != null) {
            throw new IllegalArgumentException("ReviewAttempt is decided one way, not approved and rejected both");
        }
        if (blockedBy != null && rejection != null) {
            throw new IllegalArgumentException("ReviewAttempt is stopped once, by a check or by a person, not both");
        }
        boolean stopped = blockedBy != null || rejection != null || (judgement != null && judgement.blocks());
        if (status == StepStatus.BLOCKED && !stopped) {
            throw new IllegalArgumentException("ReviewAttempt is blocked with nothing named as having stopped it");
        }
        if (status != StepStatus.BLOCKED && stopped) {
            throw new IllegalArgumentException("ReviewAttempt is " + status + " yet names something that stopped it");
        }
        if (status == StepStatus.BLOCKED && approval != null) {
            throw new IllegalArgumentException("ReviewAttempt is blocked, so nobody has passed it");
        }
        boolean overridden = approval != null && approval.kind() == ApprovalKind.OVERRIDE;
        if (replacement != null && !overridden) {
            throw new IllegalArgumentException(
                    "ReviewAttempt replaces an earlier attempt yet nobody is recorded as having overridden it");
        }
        if (overridden && replacement == null) {
            throw new IllegalArgumentException("ReviewAttempt was overridden yet names no attempt it replaced");
        }
        if (replacement != null && replacement.fromAttempt() >= attempt) {
            throw new IllegalArgumentException("ReviewAttempt " + attempt + " replaces " + replacement.fromAttempt()
                    + ", which is not an earlier attempt");
        }
        if (superseded instanceof Supersession.ByRerun && status != StepStatus.COMPLETED) {
            throw new IllegalArgumentException(
                    "ReviewAttempt was sent back for a rerun yet is " + status + ", not completed");
        }
        for (EntryReference reference : referenced) {
            requireNonNull(reference, "ReviewAttempt referenced must not hold a null entry");
        }
        referenced = List.copyOf(referenced);
    }

    private static String requireStated(String value, String role) {
        requireNonNull(value, role + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(role + " must not be blank");
        }
        return value;
    }

    /** Who passed the attempt, and as what — a model's approval is not the one a change requires. */
    public record Approval(ApprovalKind kind, SubjectId by, Instant at) {

        public Approval {
            requireNonNull(kind, "Approval kind must not be null");
            requireNonNull(by, "Approval by must not be null");
            requireNonNull(at, "Approval at must not be null");
        }
    }

    /**
     * The other arm of approve-or-reject. The note is required: a refusal a reviewer cannot read the
     * reason for leaves the author nothing to change, and it is what a lesson is drawn from.
     */
    public record Rejection(
            SubjectId by, Instant at, @DoNotLog String note) {

        public Rejection {
            requireNonNull(by, "Rejection by must not be null");
            requireNonNull(at, "Rejection at must not be null");
            requireNonNull(note, "Rejection note must not be null");
            if (note.isBlank()) {
                throw new IllegalArgumentException("Rejection " + by.value() + " states no reason");
            }
        }
    }

    /**
     * What an automated reviewer said. Material beside the attempt, not a decision on it: a reject
     * blocks, while an approve leaves the step still needing the approval a person owes it.
     *
     * @param rationale inference content, like the output it was formed about
     */
    public record Judgement(
            GateVerdict verdict,
            @DoNotLog String rationale,
            @Nullable Confidence confidence,
            String servedModel) {

        public Judgement {
            requireNonNull(verdict, "Judgement verdict must not be null");
            rationale = requireStated(rationale, "Judgement rationale");
            servedModel = requireStated(servedModel, "Judgement servedModel");
        }

        /** The half that carries: an automated reviewer may stop a step, never pass one. */
        public boolean blocks() {
            return verdict == GateVerdict.REJECT;
        }
    }
}
