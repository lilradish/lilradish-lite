package org.lilradish.lite.domain.registry

import static org.lilradish.lite.domain.identity.GroupPermission.AUTHOR_ENTRY
import static org.lilradish.lite.domain.identity.GroupPermission.REVOKE_ENTRY
import static org.lilradish.lite.domain.registry.EntryAct.LET_GO
import static org.lilradish.lite.domain.registry.EntryAct.RENAME
import static org.lilradish.lite.domain.registry.EntryAct.START_DRAFT
import static org.lilradish.lite.domain.registry.EntryAct.STOP

import org.lilradish.lite.domain.identity.GroupPermission
import spock.lang.Specification

class EntryActSpec extends Specification {

    static final Set<GroupPermission> EVERYTHING = EnumSet.allOf(GroupPermission)

    /** Renaming carries what the entry is for with it, so there is no act of saying that alone. */
    def "each act on an entry takes the one permission the group's roles reach it by, and there are no others"() {
        expect:
        EntryAct.values().collectEntries { [(it): it.permission()] } == [
                (START_DRAFT): AUTHOR_ENTRY,
                (RENAME)     : AUTHOR_ENTRY,
                (STOP)       : REVOKE_ENTRY,
                (LET_GO)     : REVOKE_ENTRY,
        ]
    }

    /** A reader draws its controls off these, so a constant renamed without its spelling staying put hides one. */
    def "each act on an entry is published under the spelling a reader draws its control by"() {
        expect:
        EntryAct.values().collectEntries { [(it): it.published()] } == [
                (START_DRAFT): "start_draft",
                (RENAME)     : "rename",
                (STOP)       : "stop",
                (LET_GO)     : "let_go",
        ]
    }

    /** A new draft only where none is under way, a stop only where none stands, a letting go only where one does. */
    def "what an entry offers turns on whether it is stopped and whether a draft is under way"() {
        expect:
        EntryAct.admitted(EVERYTHING, stopped, underWay) == admitted as Set

        where:
        stopped | underWay || admitted
        false   | false    || [START_DRAFT, RENAME, STOP]
        false   | true     || [RENAME, STOP]
        true    | false    || [START_DRAFT, RENAME, LET_GO]
        true    | true     || [RENAME, LET_GO]
    }

    def "an act whose permission is not held is never offered, whatever the entry's state"() {
        expect:
        EntryAct.admitted(permitted as Set<GroupPermission>, stopped, false) == admitted as Set

        where:
        permitted      | stopped || admitted
        [AUTHOR_ENTRY] | false   || [START_DRAFT, RENAME]
        [REVOKE_ENTRY] | true    || [LET_GO]
        []             | false   || []
    }

    def "what an entry offers cannot be asked of no permissions, and is nobody's to widen"() {
        when:
        EntryAct.admitted(null, false, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "EntryAct permitted must not be null"

        when:
        EntryAct.admitted(EVERYTHING, false, false).add(LET_GO)

        then:
        thrown(UnsupportedOperationException)
    }
}
