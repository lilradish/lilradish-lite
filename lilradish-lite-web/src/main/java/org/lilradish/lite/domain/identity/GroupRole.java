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
 * <p>What each role bundles is held here and not in the store, which keeps only which role somebody
 * holds — so changing a bundle is a release rather than a migration.
 *
 * <p>{@code OVERSEER} restates what {@code OPERATOR} carries rather than building on it, because a
 * constant cannot read another constant's bundle while it is being constructed. What makes the
 * inclusion visible is the spec, which derives each bundle from the one below it; the chain is
 * today's table, not a rule of it.
 *
 * <p>That the table is a chain under inclusion is also what makes {@link #permissionsOf} indifferent
 * to how it is written: no holding exists that tells a union of bundles apart from the widest one's.
 * The day the spec asserting the chain goes red is the day that fold starts deciding something and
 * has to be read again. What it answers with is freshly built and handed to the one type that folds
 * a holding, which wraps it and hands out only the wrapper, so no caller holds what it could widen.
 *
 * <p>A role may carry both writing and approving an entry: that nobody approves an entry they wrote is
 * a rule between people whatever they hold, and nothing in this table can express it.
 *
 * <p>The published spelling is written out rather than folded from the constant name, for the reason
 * {@link EstateAct} gives.
 */
public enum GroupRole {
    OPERATOR(
            "operator",
            GroupPermission.READ_MEMBERSHIP,
            GroupPermission.START_RUN,
            GroupPermission.ANSWER_STEP,
            GroupPermission.READ_OWN_RUNS,
            GroupPermission.AUTHOR_ENTRY,
            GroupPermission.READ_INFERENCE_CONTENT),
    OVERSEER(
            "overseer",
            GroupPermission.READ_MEMBERSHIP,
            GroupPermission.START_RUN,
            GroupPermission.ANSWER_STEP,
            GroupPermission.READ_OWN_RUNS,
            GroupPermission.AUTHOR_ENTRY,
            GroupPermission.READ_INFERENCE_CONTENT,
            GroupPermission.READ_ALL_RUNS,
            GroupPermission.REVIEW_AT_GATE,
            GroupPermission.APPROVE_ENTRY,
            GroupPermission.REVOKE_ENTRY),

    /* Every permission the vocabulary holds: a permission added later is placed in each role by a
     * decision, and this line is where the owner's is read off. */
    OWNER("owner", GroupPermission.values());

    private final String published;

    /* Held as an EnumSet and answered as a view over it: the union below is a bit-mask operation
     * only while both sides are one, and the view a caller is handed is not. */
    private final EnumSet<GroupPermission> bundled;

    private final Set<GroupPermission> permissions;

    GroupRole(String published, GroupPermission... permissions) {
        this.published = published;
        EnumSet<GroupPermission> granted = EnumSet.noneOf(GroupPermission.class);
        Collections.addAll(granted, permissions);
        this.bundled = granted;
        this.permissions = Collections.unmodifiableSet(granted);
    }

    public String published() {
        return published;
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

    /** Every role bundling the permission, and none where no role does. */
    public static Set<GroupRole> reaching(GroupPermission permission) {
        requireNonNull(permission, "GroupRole permission must not be null");
        EnumSet<GroupRole> roles = EnumSet.noneOf(GroupRole.class);
        for (GroupRole role : values()) {
            if (role.bundled.contains(permission)) {
                roles.add(role);
            }
        }
        return Collections.unmodifiableSet(roles);
    }
}
