package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A person, as this one call found them: who they are, the roles they hold in each group
 * they stand in, and what the roles they hold across the estate fold to. What they may do is folded
 * from roles wherever it is asked, because a role is the bundle a judgement is made on, and a second
 * way to state it would be a second way to disagree. On the estate axis only the fold is kept, there
 * being nothing this system decides from an estate role once it has been taken.
 *
 * <p>Somebody is an overseer in one group and an operator in another, so the group fold is taken per
 * group and read per group. Asked without one the only answer would be the union of every group,
 * which is nobody's actual standing anywhere.
 *
 * <p>The estate is the other axis and answers in a vocabulary of its own. A role held there confers
 * nothing in any group and no accumulation of group roles reaches one, so {@link #estateReach} is
 * asked without a scope and {@link #permissions} cannot be handed the estate at all. It is asked on
 * this type rather than on {@link ScopedPrincipal}: the other implementation is exercised within one
 * group and holds no estate role, so an emptiness there would read as a standing rather than as the
 * absence of the axis.
 *
 * <p>A class with a private constructor rather than a record: {@link #of} is the only way one is
 * built, so there is no component through which a permission or an act could arrive beside the
 * roles, and every fold is taken there once rather than on every ask.
 *
 * <p>A narrowing of a person is not this type holding narrower values — it is a different kind of
 * principal. {@link Delegation#of} narrows within one group, against the person it is exercised in
 * the name of, which is why nothing needs a second way in here.
 *
 * <p>No principal carries value equality, this one included: one is decided for a single call, so
 * two of them are never the same thing to compare. Whoever needs to know whether two calls were
 * made by the same person compares {@link #accountableSubject()}.
 */
public final class HumanPrincipal implements ScopedPrincipal {

    private final SubjectId subject;

    /* Held rather than read off the standings: their keys are groups, and a set of groups cannot
     * answer as a set of scopes — the estate stands beside them and is the key of none. */
    private final Set<Scope> scopes;

    private final Set<EstateAct> estateReach;

    private final Map<Scope.Group, Standing> standings;

    private HumanPrincipal(
            SubjectId subject, Set<Scope> scopes, Set<EstateAct> estateReach, Map<Scope.Group, Standing> standings) {
        this.subject = subject;
        this.scopes = scopes;
        this.estateReach = estateReach;
        this.standings = standings;
    }

    public static HumanPrincipal of(
            SubjectId subject, Set<EstateRole> estateRoles, Map<Scope.Group, Set<GroupRole>> rolesByGroup) {
        requireNonNull(subject, "HumanPrincipal subject must not be null");
        requireNonNull(estateRoles, "HumanPrincipal estateRoles must not be null");
        requireNonNull(rolesByGroup, "HumanPrincipal rolesByGroup must not be null");
        EnumSet<EstateRole> acrossTheEstate = EnumSet.noneOf(EstateRole.class);
        for (EstateRole role : estateRoles) {
            acrossTheEstate.add(requireNonNull(role, "HumanPrincipal estateRoles must not hold a null role"));
        }
        Map<Scope.Group, Standing> folded = new HashMap<>();
        for (Map.Entry<Scope.Group, Set<GroupRole>> entry : rolesByGroup.entrySet()) {
            Scope.Group group =
                    requireNonNull(entry.getKey(), "HumanPrincipal rolesByGroup must not hold a null group");
            Set<GroupRole> declared =
                    requireNonNull(entry.getValue(), "HumanPrincipal rolesByGroup must not hold a null role set");
            EnumSet<GroupRole> held = EnumSet.noneOf(GroupRole.class);
            for (GroupRole role : declared) {
                held.add(requireNonNull(role, "HumanPrincipal roles must not hold a null role"));
            }
            folded.put(group, Standing.of(held));
        }
        // Read off the roles rather than off the acts folded from them: a role bundling nothing
        // still puts somebody on this axis, and an emptiness there would take them off it.
        Set<Scope> stood = new HashSet<>(folded.keySet());
        if (!acrossTheEstate.isEmpty()) {
            stood.add(Scope.ESTATE);
        }
        return new HumanPrincipal(
                subject,
                Collections.unmodifiableSet(stood),
                EstateRole.actsOf(acrossTheEstate),
                Collections.unmodifiableMap(folded));
    }

    @Override
    public SubjectId subject() {
        return subject;
    }

    /** A person is charged for their own calls. */
    @Override
    public SubjectId accountableSubject() {
        return subject;
    }

    @Override
    public Set<Scope> scopes() {
        return scopes;
    }

    /** What this person may do across the estate, in the estate's own vocabulary. */
    public Set<EstateAct> estateReach() {
        return estateReach;
    }

    @Override
    public Set<GroupPermission> permissions(Scope.Group group) {
        return standingIn(group).permissions();
    }

    @Override
    public Set<GroupRole> roles(Scope.Group group) {
        return standingIn(group).roles();
    }

    @Override
    public boolean maySurfaceContent() {
        return true;
    }

    /**
     * A group this person does not stand in answers as one they stand in holding nothing; whether
     * they are there at all is the other axis, and {@link #scopes()} alone answers it.
     */
    Standing standingIn(Scope.Group group) {
        requireNonNull(group, "HumanPrincipal group must not be null");
        return standings.getOrDefault(group, Standing.NOWHERE);
    }
}
