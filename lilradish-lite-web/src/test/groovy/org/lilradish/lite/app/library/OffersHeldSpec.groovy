package org.lilradish.lite.app.library

import org.lilradish.lite.app.group.GroupRoles
import org.lilradish.lite.domain.identity.GroupId
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.registry.EntryVersionId
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.Specification

class OffersHeldSpec extends Specification {

    def "a group reached for anything but starting a run holds no version, the store never asked: #permission"() {
        given:
        def database = Mock(JdbcClient)
        /* Past the private constructor on purpose: only GroupRoles makes the token, and no role pairs with the
         * permission this wants one for. */
        def reached = new GroupRoles.StillReached(
                new GroupId(UUID.fromString("00000003-0000-4000-8000-000000001901")), permission, EnumSet.of(permission))

        when:
        OffersHeld.held(database, reached, new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000001901")))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "OffersHeld reached must be for starting a run, not ${permission}"
        0 * database._

        where:
        permission << GroupPermission.values().findAll { it != GroupPermission.START_RUN }
    }
}
