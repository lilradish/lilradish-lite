package org.lilradish.lite.app.group

import org.lilradish.lite.domain.identity.GroupId
import spock.lang.Specification

class GroupListsSpec extends Specification {

    /** One spelling whatever case the identifier arrived in, so a cursor bound to it binds in one group only. */
    def "a list read within a group is read within the group's identifier in its standard form"() {
        expect:
        GroupLists.within(new GroupId(UUID.fromString(spelled))) == "00000003-0000-4000-8000-00000000000a"

        and: "and another group is read within another spelling"
        GroupLists.within(new GroupId(UUID.fromString("00000003-0000-4000-8000-00000000000b"))) !=
                GroupLists.within(new GroupId(UUID.fromString(spelled)))

        where:
        spelled << ["00000003-0000-4000-8000-00000000000a", "00000003-0000-4000-8000-00000000000A"]
    }

    def "no scope is spelt for no group at all"() {
        when:
        GroupLists.within(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "GroupLists group must not be null"
    }
}
