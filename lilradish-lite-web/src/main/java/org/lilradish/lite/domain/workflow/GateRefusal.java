package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.identity.GroupPermission;

/**
 * Why a principal may not give the human decision a gate wants. Each case names the rule standing in
 * the way and carries only what that rule is stated in terms of — a permission, and nothing wider.
 * There is nothing here to put the candidate into, so two ineligible callers get the same answer and
 * neither learns anything about the other, which a sentence built around the caller would claim and
 * not do.
 *
 * <p>Nothing here renders. A refusal reaches a person through whichever transport asked for the
 * decision, and the wording is that transport's to choose.
 */
public sealed interface GateRefusal {

    /** The judgement at this gate is a model's, so there is no human decision to be given at it. */
    record DecidedByModel() implements GateRefusal {}

    /** A delegation and an actor that exists in code are both non-human, however wide their grant. */
    record NotAPerson() implements GateRefusal {}

    /** The candidate does not stand where the run does, so the run is not theirs to see, let alone decide. */
    record NotInScope() implements GateRefusal {}

    record PermissionNotHeld(GroupPermission permission) implements GateRefusal {
        public PermissionNotHeld {
            requireNonNull(permission, "PermissionNotHeld permission must not be null");
        }
    }

    record NotTheEntryOwner() implements GateRefusal {}

    record InitiatorMayNotDecide() implements GateRefusal {}
}
