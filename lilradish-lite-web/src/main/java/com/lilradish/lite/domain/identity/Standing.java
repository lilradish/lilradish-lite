package com.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * Roles and their permissions are folded once at principal construction, preventing a second source of authority.
 * The private constructor admits only named construction or {@link #narrowedTo}; the latter deliberately keeps
 * the owner's roles while withholding some permissions, which a public component constructor must not bypass.
 *
 * <p>The permissions view already renders the fold. Omitting its backing set leaves room in the delegation's log budget.
 */
final class Standing {

    /** Cached answer for an absent group, avoiding a fresh allocation on each ask. */
    static final Standing NOWHERE = of(EnumSet.noneOf(GroupRole.class));

    private final Set<GroupRole> roles;

    /* Held as an EnumSet and answered as a view over it: the narrowing below is a bit-mask
     * operation only while both sides are one, and the view a caller is handed is not. */
    @DoNotLog
    private final EnumSet<GroupPermission> granted;

    private final Set<GroupPermission> permissions;

    private Standing(Set<GroupRole> roles, EnumSet<GroupPermission> granted) {
        this.roles = roles;
        this.granted = granted;
        this.permissions = Collections.unmodifiableSet(granted);
    }

    /**
     * What a holding of roles folds to, the two parts agreeing. The roles are copied and not
     * wrapped: a caller keeping the set it handed in could widen them out from under the fold.
     */
    static Standing of(EnumSet<GroupRole> held) {
        requireNonNull(held, "Standing held must not be null");
        return new Standing(Collections.unmodifiableSet(EnumSet.copyOf(held)), GroupRole.permissionsOf(held));
    }

    /**
     * The roles are carried across unnarrowed: a delegation is exercised in its owner's name, so
     * whatever is matched to a role of theirs is matched for it too.
     */
    Standing narrowedTo(Set<GroupPermission> declaredPermissions) {
        requireNonNull(declaredPermissions, "Standing declaredPermissions must not be null");
        EnumSet<GroupPermission> narrowed = EnumSet.noneOf(GroupPermission.class);
        narrowed.addAll(declaredPermissions);
        narrowed.retainAll(granted);
        return new Standing(roles, narrowed);
    }

    Set<GroupRole> roles() {
        return roles;
    }

    Set<GroupPermission> permissions() {
        return permissions;
    }
}
