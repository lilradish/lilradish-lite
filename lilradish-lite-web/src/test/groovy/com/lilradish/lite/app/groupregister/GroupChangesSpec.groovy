package com.lilradish.lite.app.groupregister

import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.GroupRole
import spock.lang.Specification


class GroupChangesSpec extends Specification {


    def "the role a group's first member is given is the one role that may change its membership"() {
        expect:
        GroupChanges.FOUNDING == GroupRole.OWNER
    }


    def "a permission more than one role reaches names no role to give, and says how many reach it"() {
        when:
        GroupChanges.soleRoleReaching(GroupPermission.READ_MEMBERSHIP)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "3 roles may READ_MEMBERSHIP where the estate sets exactly one"
    }
}
