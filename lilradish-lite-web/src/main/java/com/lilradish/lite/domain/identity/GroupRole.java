package com.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Identity claims locate a person; only this system grants group roles. Bundles confer no estate acts.
 * The store keeps role holdings, not bundles, so changing a bundle requires a release rather than a migration.
 *
 * <p>{@code OVERSEER} repeats {@code OPERATOR}'s grants because enum construction cannot read another constant's bundle.
 * Bundles currently form a chain under inclusion, verified by tests rather than types; if that changes, review the union policy.
 * {@link #permissionsOf} builds a fresh set that the standing wraps, so callers cannot widen a principal's permissions.
 *
 * <p>Writing and approving may share a role; the prohibition on approving one's own work belongs outside this table.
 * Published spellings are explicit for the reason {@link EstateAct} gives.
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

    /** A role's bundle alone is not a principal's effective permissions; ask the principal. */
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
