package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The roles a group starts with. They are this system's own, not the identity provider's: what the
 * provider asserts locates a person and grants nothing, so a role is reached only through what this
 * system holds against that person.
 *
 * <p>What a role bundles is held inside a group and nowhere else: no standing across the estate is
 * reached through one of these, which is why a holding of them is keyed by {@link Scope.Group} and
 * the estate cannot be written as that key.
 *
 * <p>These bundles are data whose only writer is a migration — changing a role is a release — and
 * the table holding them is not written yet, so until it is they sit here. What the store keeps is
 * only which role somebody holds.
 *
 * <p>{@code OVERSEER} restates what {@code OPERATOR} carries rather than building on it, because a
 * constant cannot read another constant's bundle while it is being constructed. What states the
 * inclusion as a rule is the spec, which derives each bundle from the one below it.
 *
 * <p>That the table is a chain under inclusion is also what makes {@link #permissionsOf} indifferent
 * to how it is written: no holding exists that tells a union of bundles apart from the widest one's.
 * The day the spec asserting the chain goes red is the day that fold starts deciding something and
 * has to be read again. What it answers with is freshly built and handed to the one type that folds
 * a holding, which wraps it and hands out only the wrapper, so no caller holds what it could widen.
 *
 * <p>Whether one person may both raise a change and pass it is declared per step, on the gate that
 * step must pass, and compared on the accountable subject at the moment the decision is made. This
 * table says nothing about it, and a role carrying both an authority over a step and an authority
 * over an entry is not a defect.
 */
public enum GroupRole {
    OPERATOR(
            GroupPermission.READ_MEMBERSHIP,
            GroupPermission.START_RUN,
            GroupPermission.READ_OWN_RUNS,
            GroupPermission.AUTHOR_ENTRY,
            GroupPermission.DISMISS_WORK),
    OVERSEER(
            GroupPermission.READ_MEMBERSHIP,
            GroupPermission.START_RUN,
            GroupPermission.READ_OWN_RUNS,
            GroupPermission.AUTHOR_ENTRY,
            GroupPermission.DISMISS_WORK,
            GroupPermission.READ_ALL_RUNS,
            GroupPermission.REVIEW_AT_GATE,
            GroupPermission.APPROVE_ENTRY,
            GroupPermission.REVOKE_ENTRY,
            GroupPermission.DECLARE_TOKEN_CEILING,
            GroupPermission.RAISE_RUN_ALLOWANCE),

    /* Every permission a role may carry rather than a list of them: one added to the vocabulary
     * reaches every owner of every group without anybody deciding it a second time. */
    OWNER(GroupPermission.values());

    /* Held as an EnumSet and answered as a view over it: the union below is a bit-mask operation
     * only while both sides are one, and the view a caller is handed is not. */
    private final EnumSet<GroupPermission> bundled;

    private final Set<GroupPermission> permissions;

    GroupRole(GroupPermission... permissions) {
        EnumSet<GroupPermission> granted = EnumSet.noneOf(GroupPermission.class);
        Collections.addAll(granted, permissions);
        this.bundled = granted;
        this.permissions = Collections.unmodifiableSet(granted);
    }

    /**
     * What one role bundles, which is a question about this table and not about anybody. Reading a
     * standing off it would be reading one out of a single part of itself.
     */
    Set<GroupPermission> permissions() {
        return permissions;
    }

    /**
     * Everything the roles bundle, freshly built and the caller's to keep. Roles add grants, so two
     * of them are read together as the union.
     */
    static EnumSet<GroupPermission> permissionsOf(Collection<GroupRole> roles) {
        requireNonNull(roles, "GroupRole roles must not be null");
        EnumSet<GroupPermission> granted = EnumSet.noneOf(GroupPermission.class);
        for (GroupRole role : roles) {
            requireNonNull(role, "GroupRole roles must not hold a null role");
            granted.addAll(role.bundled);
        }
        return granted;
    }
}
