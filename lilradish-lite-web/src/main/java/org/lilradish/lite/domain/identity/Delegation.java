package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * A non-human actor whose output a person does read as an answer — an agent step, a registered
 * schedule — carrying a human owner, a declared holding, and a life that ends at an expiry or at a
 * revocation. Its label is what acts and its owner is what the call is charged to, the two being
 * different answers here.
 *
 * <p>It is registered for one group and narrows what its owner holds there. What somebody holds in
 * one group, delegated, is the sentence this has to be able to say, and a single grant read across
 * every group cannot say it: it would hand the delegation the owner's widest standing anywhere
 * wherever it acted.
 *
 * <p>A class with a private constructor rather than a record: {@link #of} is the only way one is
 * built, and all three bounds are applied there — the declared permissions narrow what the owner
 * holds in that one group and can never widen it, a registration that is no longer live yields no
 * delegation at all, and neither does one drawn in a group its owner does not stand in. One
 * therefore cannot out-reach or out-scope the person it is exercised in the name of, nor outlive the
 * registration behind it, and there is no second route in that would sidestep any of the three. The
 * instant the whole call is decided against is taken there as well, so the time bound is not the one
 * bound every caller has to remember to ask about.
 *
 * <p>The group is kept out of the rendering rather than withheld from it: the registration one slot
 * up already names the group, so the slot would say the same thing twice. What the room that buys is
 * spent on is the bounds, which are what a line about a refused call is read for and which a line
 * run past its budget loses off the end. A spec holds the margin outright, so a slot added back here
 * or on either nested value fails there rather than in a log.
 */
public final class Delegation implements ScopedPrincipal {

    private final Registration registration;

    /* Held rather than built per ask, and kept out of the rendering for the room the class comment
     * names rather than to withhold anything. */
    @DoNotLog
    private final Set<Scope> scopes;

    private final Standing standing;

    private Delegation(Registration registration, Set<Scope> scopes, Standing standing) {
        this.registration = registration;
        this.scopes = scopes;
        this.standing = standing;
    }

    /**
     * The registration has to be one registered to this owner, or the narrowing is taken from the
     * owner handed in while the call is charged to whoever the registration names.
     */
    public static Delegation of(Registration registration, HumanPrincipal owner, Instant now) {
        requireNonNull(registration, "Delegation registration must not be null");
        requireNonNull(owner, "Delegation owner must not be null");
        requireNonNull(now, "Delegation now must not be null");
        if (!registration.owner().equals(owner.accountableSubject())) {
            throw new IllegalArgumentException("Delegation is registered to "
                    + registration.owner().value() + ", not to "
                    + owner.accountableSubject().value());
        }
        if (!registration.isLive(now)) {
            throw new IllegalArgumentException(
                    "Delegation " + registration.label().value() + " is no longer live at " + now);
        }
        if (!owner.scopes().contains(registration.group())) {
            throw new IllegalArgumentException("Delegation "
                    + registration.label().value() + " is registered in a group "
                    + owner.accountableSubject().value() + " does not stand in");
        }
        return new Delegation(
                registration,
                Set.of(registration.group()),
                owner.standingIn(registration.group()).narrowedTo(registration.declaredPermissions()));
    }

    /** What the delegation was registered as, so whoever holds one can ask whether it is spent. */
    public Registration registration() {
        return registration;
    }

    @Override
    public SubjectId subject() {
        return registration.label();
    }

    @Override
    public SubjectId accountableSubject() {
        return registration.owner();
    }

    @Override
    public Set<Scope> scopes() {
        return scopes;
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

    private Standing standingIn(Scope.Group group) {
        requireNonNull(group, "Delegation group must not be null");
        return scopes.contains(group) ? standing : Standing.NOWHERE;
    }

    /**
     * What a delegation was registered as, and what bounds it.
     *
     * @param group the one group it may be exercised in, its owner's standing there being what it narrows
     * @param revokedAt when the registration was withdrawn, absent while it stands
     */
    public record Registration(
            UUID delegationId,
            SubjectId label,
            SubjectId owner,
            Scope.Group group,
            Instant expiresAt,
            @Nullable Instant revokedAt,
            Set<GroupPermission> declaredPermissions) {

        public Registration {
            requireNonNull(delegationId, "Registration delegationId must not be null");
            requireNonNull(label, "Registration label must not be null");
            requireNonNull(owner, "Registration owner must not be null");
            requireNonNull(group, "Registration group must not be null");
            requireNonNull(expiresAt, "Registration expiresAt must not be null");
            requireNonNull(declaredPermissions, "Registration declaredPermissions must not be null");
            EnumSet<GroupPermission> declared = EnumSet.noneOf(GroupPermission.class);
            for (GroupPermission permission : declaredPermissions) {
                declared.add(
                        requireNonNull(permission, "Registration declaredPermissions must not hold a null permission"));
            }
            declaredPermissions = Collections.unmodifiableSet(declared);
        }

        /**
         * An expiry and a revocation are one question, so one predicate answers both. Either
         * instant is itself spent: a delegation is not exercised on the boundary.
         */
        public boolean isLive(Instant now) {
            requireNonNull(now, "Registration now must not be null");
            return now.isBefore(expiresAt) && (revokedAt == null || now.isBefore(revokedAt));
        }
    }
}
