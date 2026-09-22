package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * What one principal holds in one group: the roles, and the fold taken from them. The fold is taken
 * where the principal is built rather than on an ask, and the two are held together because a second
 * place to state it would be a second place for it to disagree.
 *
 * <p>A class with a private constructor rather than a record, for the reason {@link HumanPrincipal}
 * is one. {@link #narrowedTo} builds a standing whose two parts disagree on purpose — a delegation is
 * granted less than its roles bundle — so the constructor that can say that must not be reachable as
 * a component list. Two named factories are the only ways one is built, and each says which of the
 * two it is making.
 *
 * <p>The fold is kept out of the rendering as the same set written twice — the view over it renders
 * beside it — and not as anything withheld. What the room that buys is spent on is the line a
 * delegation renders to, which carries a standing nested inside it and whose budget {@link
 * Delegation} explains.
 */
final class Standing {

    /**
     * What a group the principal does not stand in answers with. Held rather than built per ask, so
     * the answer costs nothing and is the same instance wherever it is given.
     */
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
     * whatever a retrieval would match to a role of theirs it would match for it too.
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
