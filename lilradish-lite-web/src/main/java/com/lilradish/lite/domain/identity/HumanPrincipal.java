package com.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Permissions derive only from roles, folded once when {@link #of} builds the call's principal.
 * Only the estate fold is retained because no later judgement here needs the estate roles.
 *
 * <p>Group standings are folded and read per group; their union would not describe authority in any one group.
 * Estate acts form a separate axis, conferring no group permissions. {@link #estateReach} belongs here, not on
 * {@link ScopedPrincipal}: delegations have no estate axis, rather than an empty standing on it.
 *
 * <p>The private constructor prevents permissions arriving independently of roles. Narrowing is a different
 * principal kind: {@link Delegation#of} narrows the owner's standing within one group, so this type needs no other entry.
 *
 * <p>Principals represent single calls and have no value equality; compare {@link #accountableSubject()} for the caller.
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
            // A key here is membership, and somebody holding nothing in a group is not one of its members.
            if (held.isEmpty()) {
                throw new IllegalArgumentException("HumanPrincipal rolesByGroup must not hold a group with no role");
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
     * A group this person is not a member of answers as holding nothing; {@link #scopes()} alone says
     * whether they are one.
     */
    Standing standingIn(Scope.Group group) {
        requireNonNull(group, "HumanPrincipal group must not be null");
        return standings.getOrDefault(group, Standing.NOWHERE);
    }
}
