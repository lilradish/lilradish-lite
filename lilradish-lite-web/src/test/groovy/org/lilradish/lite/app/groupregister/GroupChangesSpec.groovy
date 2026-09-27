package org.lilradish.lite.app.groupregister

import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import spock.lang.Specification

/** The one rule of creating a group that is decided without a database: which role the first member is given. */
class GroupChangesSpec extends Specification {

    /** Read off the roles' own bundles, so it is the owner today because the owner alone may today. */
    def "the role a group's first member is given is the one role that may change its membership"() {
        expect:
        GroupChanges.FOUNDING == GroupRole.OWNER
    }

    /** A table where several roles may is one where which the estate sets is a decision nobody took. */
    def "a permission more than one role reaches names no role to give, and says how many reach it"() {
        when:
        GroupChanges.soleRoleReaching(GroupPermission.READ_MEMBERSHIP)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "3 roles may READ_MEMBERSHIP where the estate sets exactly one"
    }
}
