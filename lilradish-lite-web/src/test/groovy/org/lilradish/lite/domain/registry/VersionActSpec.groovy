package org.lilradish.lite.domain.registry

import static org.lilradish.lite.domain.identity.GroupPermission.APPROVE_ENTRY
import static org.lilradish.lite.domain.identity.GroupPermission.AUTHOR_ENTRY
import static org.lilradish.lite.domain.identity.GroupPermission.REVOKE_ENTRY
import static org.lilradish.lite.domain.registry.VersionAct.APPROVE
import static org.lilradish.lite.domain.registry.VersionAct.RETIRE
import static org.lilradish.lite.domain.registry.VersionAct.SUBMIT
import static org.lilradish.lite.domain.registry.VersionAct.WITHDRAW
import static org.lilradish.lite.domain.registry.VersionAct.WRITE
import static org.lilradish.lite.domain.registry.VersionStanding.DRAFT
import static org.lilradish.lite.domain.registry.VersionStanding.IN_SERVICE
import static org.lilradish.lite.domain.registry.VersionStanding.RETIRED
import static org.lilradish.lite.domain.registry.VersionStanding.SUBMITTED

import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupPermission
import spock.lang.Specification

class VersionActSpec extends Specification {

    static final Set<GroupPermission> EVERYTHING = EnumSet.allOf(GroupPermission)

    def "each act takes the one permission the group's roles reach it by"() {
        expect:
        VersionAct.values().collectEntries { [(it): it.permission()] } == [
                (WRITE)   : AUTHOR_ENTRY,
                (SUBMIT)  : AUTHOR_ENTRY,
                (WITHDRAW): AUTHOR_ENTRY,
                (APPROVE) : APPROVE_ENTRY,
                (RETIRE)  : REVOKE_ENTRY,
        ]
    }

    def "each act is published under the spelling a reader draws its control by"() {
        expect:
        VersionAct.values().collectEntries { [(it): it.published()] } == [
                (WRITE)   : "write",
                (SUBMIT)  : "submit",
                (WITHDRAW): "withdraw",
                (APPROVE) : "approve",
                (RETIRE)  : "retire",
        ]
    }

    /** Every act against every standing: each moves a version on from exactly one, and nothing returns from service. */
    def "an act is admitted from the one standing it moves a version on from, and refused from every other"() {
        expect:
        act.refusal(standing, false) == (standing == from ? null : RefusalCode.VERSION_STANDING_REFUSES)

        where:
        [act, standing] << [VersionAct.values().toList(), VersionStanding.values().toList()].combinations()
        from = [(WRITE): DRAFT, (SUBMIT): DRAFT, (WITHDRAW): SUBMITTED, (APPROVE): SUBMITTED, (RETIRE): IN_SERVICE][act]
    }

    def "nobody approves what they wrote, and writing closes no other act to them"() {
        expect:
        act.refusal(standing, true) == refusal

        where:
        act      | standing   || refusal
        APPROVE  | SUBMITTED  || RefusalCode.APPROVER_WROTE_VERSION
        WRITE    | DRAFT      || null
        SUBMIT   | DRAFT      || null
        WITHDRAW | SUBMITTED  || null
        RETIRE   | IN_SERVICE || null
    }

    /** A standing that refuses the act is said before authorship, which it makes beside the point. */
    def "a writer asking to approve a version not waiting on approval is told of its standing"() {
        expect:
        APPROVE.refusal(standing, true) == RefusalCode.VERSION_STANDING_REFUSES

        where:
        standing << [DRAFT, IN_SERVICE, RETIRED]
    }

    def "an act cannot be judged against no standing"() {
        when:
        SUBMIT.refusal(null, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "VersionAct standing must not be null"
    }

    def "what a version admits is every act whose permission is held and whose refusal is nothing"() {
        expect:
        VersionAct.admitted(standing, permitted as Set<GroupPermission>, wroteIt) == admitted as Set

        where:
        standing   | permitted                     | wroteIt || admitted
        DRAFT      | EVERYTHING                    | true    || [WRITE, SUBMIT]
        DRAFT      | [APPROVE_ENTRY]               | false   || []
        SUBMITTED  | EVERYTHING                    | false   || [WITHDRAW, APPROVE]
        SUBMITTED  | EVERYTHING                    | true    || [WITHDRAW]
        SUBMITTED  | [APPROVE_ENTRY]               | false   || [APPROVE]
        IN_SERVICE | EVERYTHING                    | true    || [RETIRE]
        IN_SERVICE | [AUTHOR_ENTRY, APPROVE_ENTRY] | false   || []
        RETIRED    | EVERYTHING                    | false   || []
    }

    def "what a version admits cannot be asked of no permissions, and is nobody's to widen"() {
        when:
        VersionAct.admitted(DRAFT, null, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "VersionAct permitted must not be null"

        when:
        VersionAct.admitted(DRAFT, EVERYTHING, false).add(RETIRE)

        then:
        thrown(UnsupportedOperationException)
    }
}
