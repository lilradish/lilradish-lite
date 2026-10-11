package com.lilradish.lite.domain.registry

import static com.lilradish.lite.domain.identity.GroupPermission.AUTHOR_ENTRY
import static com.lilradish.lite.domain.identity.GroupPermission.REVOKE_ENTRY
import static com.lilradish.lite.domain.registry.EntryAct.LET_GO
import static com.lilradish.lite.domain.registry.EntryAct.RENAME
import static com.lilradish.lite.domain.registry.EntryAct.START_DRAFT
import static com.lilradish.lite.domain.registry.EntryAct.STOP

import spock.lang.Specification

class EntryActSpec extends Specification {

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

    def "an entry publishes the same immutable state acts without a reader's permissions"() {
        expect:
        EntryAct.stateActs(stopped, underWay) == acts as Set

        where:
        stopped | underWay || acts
        false   | false    || [START_DRAFT, RENAME, STOP]
        false   | true     || [RENAME, STOP]
        true    | false    || [START_DRAFT, RENAME, LET_GO]
        true    | true     || [RENAME, LET_GO]
    }

    def "an entry's state facts cannot be widened"() {
        when:
        EntryAct.stateActs(false, false).add(LET_GO)

        then:
        thrown(UnsupportedOperationException)
    }

}
