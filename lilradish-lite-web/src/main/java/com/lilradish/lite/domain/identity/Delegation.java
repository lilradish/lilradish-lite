package com.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * Acts under its own label but charges its human owner, narrowing only the owner's standing in its registered group.
 *
 * <p>A private constructor keeps {@link #of} the sole entry: it checks group membership, expiry and revocation
 * at one instant, then intersects declared permissions with the owner's standing. A cross-group grant would widen authority.
 *
 * <p>The registration already renders the group. Omitting it here leaves the log's length budget for bounds;
 * the rendering test pins that budget, including nested values.
 */
public final class Delegation implements ScopedPrincipal {

    private final Registration registration;

    /* Held rather than built per ask. */
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

        /** Expiry and revocation each spend the delegation at that instant; it is not exercised on the boundary. */
        public boolean isLive(Instant now) {
            requireNonNull(now, "Registration now must not be null");
            return now.isBefore(expiresAt) && (revokedAt == null || now.isBefore(revokedAt));
        }
    }
}
