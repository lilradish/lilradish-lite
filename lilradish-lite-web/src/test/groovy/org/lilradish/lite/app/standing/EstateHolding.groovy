package org.lilradish.lite.app.standing

import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.HumanPrincipal
import org.lilradish.lite.domain.identity.SubjectId

/**
 * What the store answers for somebody holding these estate roles and in no group, and for somebody
 * holding none, nothing. Beside the package-private type it builds, which it cannot reach from elsewhere.
 */
final class EstateHolding {

    static final SubjectId SOMEBODY = new SubjectId(UUID.fromString("00000001-0000-4000-8000-0000000000e1"))

    private EstateHolding() {}

    static Optional<Holdings.Held> of(EstateRole... roles) {
        roles.length == 0
                ? Optional.empty()
                : Optional.of(new Holdings.Held(HumanPrincipal.of(SOMEBODY, roles as Set, [:]), []))
    }
}
