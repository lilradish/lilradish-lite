package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.HumanPrincipal;
import org.lilradish.lite.domain.identity.Principal;
import org.lilradish.lite.domain.identity.Scope;
import org.lilradish.lite.domain.identity.SubjectId;

/**
 * What a step declares about the gate it must pass. Five questions, independent of one another: who
 * judges, which principal qualifies, whether that principal may be the one who started the run, what
 * they must be permitted to do, and how long they have before nobody has answered. A gate names the
 * authority it confers, because authority without the context to use it, or that context without the
 * authority, would make the gate ceremonial.
 *
 * <p>{@code decisionBudget} is a component and has no default. A figure held anywhere but in the
 * definition is one the definition can be published without, and a gate nobody is obliged to answer
 * leaves the run waiting for ever — an outcome no decision at the gate ever named.
 *
 * <p>Two things are deliberately left to a layer that does not exist yet: nothing pairs a
 * {@link Judge} with the {@code ApprovalKind} an approval is recorded as, and
 * {@code decisionBudget} is declared here and applied nowhere here.
 *
 * @param requiredPermissions what the gate demands of whoever answers it, which may be nothing
 */
public record ApprovalGate(
        Judge judge,
        Approver approver,
        boolean approverMustDifferFromInitiator,
        Set<GroupPermission> requiredPermissions,
        Duration decisionBudget) {

    /**
     * A span no pair of instants could ever cover is not a budget at all. It is not an overflow
     * guard and cannot be one: the ceiling a deadline has to fit under is measured from the instant
     * the gate is reached, which is unknowable here, so whatever computes a deadline still has to
     * guard its own arithmetic.
     */
    private static final Duration UNCOUNTABLE = Duration.between(Instant.MIN, Instant.MAX);

    /** An EnumSet, so the permission a refusal names is the same one on every call. */
    public ApprovalGate {
        requireNonNull(judge, "ApprovalGate judge must not be null");
        requireNonNull(approver, "ApprovalGate approver must not be null");
        requireNonNull(requiredPermissions, "ApprovalGate requiredPermissions must not be null");
        requireNonNull(decisionBudget, "ApprovalGate decisionBudget must not be null");
        if (decisionBudget.isZero() || decisionBudget.isNegative()) {
            throw new IllegalArgumentException(
                    "ApprovalGate decisionBudget must leave time to decide in, not " + decisionBudget);
        }
        if (decisionBudget.compareTo(UNCOUNTABLE) > 0) {
            throw new IllegalArgumentException(
                    "ApprovalGate decisionBudget is longer than any two instants are apart: " + decisionBudget);
        }
        EnumSet<GroupPermission> demanded = EnumSet.noneOf(GroupPermission.class);
        for (GroupPermission permission : requiredPermissions) {
            demanded.add(
                    requireNonNull(permission, "ApprovalGate requiredPermissions must not hold a null permission"));
        }
        requiredPermissions = Collections.unmodifiableSet(demanded);
    }

    /**
     * Null where this principal may give the human decision this gate wants, otherwise the rule that
     * stands in the way, drawn from the gate and never from the candidate; see {@link GateRefusal}.
     *
     * <p>Sound only while {@code group}, {@code initiator} and {@code entryOwner} are read from the
     * run and from the entry being acted on. The group in particular is the run's: a run inherits the
     * scope of the work it was started against,
     * which is not the scope of the workflow it runs — that one may be owned elsewhere and published.
     *
     * <p>A group rather than any scope, because a gate is answered out of what a role bundles and a
     * role is held in a group: a gate reached in the estate is not a question with a wrong answer
     * here, it is one that cannot be asked.
     *
     * <p>Whether somebody stands in a group is otherwise the business of whatever filters rows, and
     * it is asked here for one reason: a gate may demand no permission and qualify anybody, and
     * nothing else on this path would then stop a run being decided by somebody who may not see it.
     */
    public @Nullable GateRefusal refusalFor(
            Principal candidate, Scope.Group group, SubjectId initiator, SubjectId entryOwner) {
        requireNonNull(candidate, "ApprovalGate candidate must not be null");
        requireNonNull(group, "ApprovalGate group must not be null");
        requireNonNull(initiator, "ApprovalGate initiator must not be null");
        requireNonNull(entryOwner, "ApprovalGate entryOwner must not be null");
        // Exhaustive rather than a predicate on the judge: a fourth shape of judgement has to be
        // answered here, where the consequence is, instead of inheriting an answer written before it.
        boolean decidedWithoutAPerson =
                switch (judge) {
                    case Judge.Model _ -> true;
                    case Judge.Person _ -> false;
                    case Judge.ModelThenPerson _ -> false;
                };
        if (decidedWithoutAPerson) {
            return new GateRefusal.DecidedByModel();
        }
        if (!(candidate instanceof HumanPrincipal person)) {
            return new GateRefusal.NotAPerson();
        }
        // Membership rather than a role held there: standing in a group holding no role is standing
        // there, and what roles somebody holds is what the loop below asks. Refused before it, so
        // whoever may not see the run learns nothing about what deciding it would have demanded.
        if (!person.scopes().contains(group)) {
            return new GateRefusal.NotInScope();
        }
        for (GroupPermission required : requiredPermissions) {
            if (!person.may(required, group)) {
                return new GateRefusal.PermissionNotHeld(required);
            }
        }
        // Both comparisons below name the accountable subject, which is the presented one for every
        // shape that reaches this far — which is why the choice has to be stated rather than dropped.
        GateRefusal unqualified =
                switch (approver) {
                    case Approver.AnyPermitted _ -> null;
                    case Approver.EntryOwner _ ->
                        person.accountableSubject().equals(entryOwner) ? null : new GateRefusal.NotTheEntryOwner();
                };
        if (unqualified != null) {
            return unqualified;
        }
        if (approverMustDifferFromInitiator && person.accountableSubject().equals(initiator)) {
            return new GateRefusal.InitiatorMayNotDecide();
        }
        return null;
    }

    /**
     * Who forms the judgement at this gate. Each case that consults a model carries the prompt and
     * the selector doing the judging, so a model judge with nothing to judge by, and a gate no model
     * touches that nevertheless names a prompt, are both things that cannot be written down.
     */
    public sealed interface Judge {

        record Person() implements Judge {}

        /** No human decision is on offer at one of these: the judgement is the gate's own answer. */
        record Model(PromptRef prompt, ModelSelector model) implements Judge {
            public Model {
                requireNonNull(prompt, "Model prompt must not be null");
                requireNonNull(model, "Model model must not be null");
            }
        }

        /** The judgement is material the person decides in front of, never the decision itself. */
        record ModelThenPerson(PromptRef prompt, ModelSelector model) implements Judge {
            public ModelThenPerson {
                requireNonNull(prompt, "ModelThenPerson prompt must not be null");
                requireNonNull(model, "ModelThenPerson model must not be null");
            }
        }
    }

    /** Which principal qualifies, beside what the gate requires of anyone answering it. */
    public sealed interface Approver {

        record AnyPermitted() implements Approver {}

        record EntryOwner() implements Approver {}
    }
}
