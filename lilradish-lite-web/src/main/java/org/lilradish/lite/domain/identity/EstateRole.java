package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The roles the estate has. They are this system's own, as a group's are: what an identity provider
 * asserts locates a person and grants nothing, so a role is reached only through what this system
 * holds against that person. What no group role has is where the first one comes from —
 * nothing inside this system grants it, and a deployment arrives already holding one.
 *
 * <p>Unlike {@link GroupRole}, these bundles form no chain, so folding two of them is not the same
 * as reading the wider one: {@link #actsOf} decides something here rather than restating a bundle.
 *
 * <p>What each role bundles is held here and not in the store, which keeps only which role somebody
 * holds — so changing a bundle is a release rather than a migration.
 *
 * <p>The published spelling is written out rather than folded from the constant name, for the reason
 * {@link EstateAct} gives.
 */
public enum EstateRole {

    /** Who is in the pool, which estate roles they hold, and which groups exist. */
    STEWARD("steward", EstateAct.KEEP_POOL, EstateAct.GRANT_ESTATE_ROLE, EstateAct.KEEP_GROUP_REGISTER),

    /** The standing that reads what the estate measures of itself and checks its soundness, and grants nothing. */
    WATCHER("watcher", EstateAct.CHECK_SOUNDNESS, EstateAct.READ_MEASUREMENTS);

    private final String published;

    /* Held as an EnumSet and answered as a view over it: the union below is a bit-mask operation
     * only while both sides are one, and the view a caller is handed is not. */
    private final EnumSet<EstateAct> bundled;

    private final Set<EstateAct> acts;

    EstateRole(String published, EstateAct... granted) {
        this.published = published;
        EnumSet<EstateAct> bundle = EnumSet.noneOf(EstateAct.class);
        Collections.addAll(bundle, granted);
        this.bundled = bundle;
        this.acts = Collections.unmodifiableSet(bundle);
    }

    public String published() {
        return published;
    }

    public Set<EstateAct> acts() {
        return acts;
    }

    /**
     * Everything the roles bundle, and nothing at all for none of them. Roles add grants, so two of
     * them are read together as the union: holding a second role never withdraws what the first one
     * carried.
     */
    public static Set<EstateAct> actsOf(Collection<EstateRole> roles) {
        requireNonNull(roles, "EstateRole roles must not be null");
        EnumSet<EstateAct> granted = EnumSet.noneOf(EstateAct.class);
        for (EstateRole role : roles) {
            requireNonNull(role, "EstateRole roles must not hold a null role");
            granted.addAll(role.bundled);
        }
        return Collections.unmodifiableSet(granted);
    }

    /** Every role bundling the act, and none where no role does. */
    public static Set<EstateRole> reaching(EstateAct act) {
        requireNonNull(act, "EstateRole act must not be null");
        EnumSet<EstateRole> roles = EnumSet.noneOf(EstateRole.class);
        for (EstateRole role : values()) {
            if (role.bundled.contains(act)) {
                roles.add(role);
            }
        }
        return Collections.unmodifiableSet(roles);
    }
}
