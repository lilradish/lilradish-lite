package com.lilradish.lite.domain.registry

import static com.lilradish.lite.domain.identity.GroupPermission.APPROVE_ENTRY
import static com.lilradish.lite.domain.identity.GroupPermission.AUTHOR_ENTRY
import static com.lilradish.lite.domain.identity.GroupPermission.REVOKE_ENTRY
import static com.lilradish.lite.domain.registry.VersionAct.APPROVE
import static com.lilradish.lite.domain.registry.VersionAct.RETIRE
import static com.lilradish.lite.domain.registry.VersionAct.SUBMIT
import static com.lilradish.lite.domain.registry.VersionAct.WITHDRAW
import static com.lilradish.lite.domain.registry.VersionAct.WRITE
import static com.lilradish.lite.domain.registry.VersionStanding.DRAFT
import static com.lilradish.lite.domain.registry.VersionStanding.IN_SERVICE
import static com.lilradish.lite.domain.registry.VersionStanding.RETIRED
import static com.lilradish.lite.domain.registry.VersionStanding.SUBMITTED

import com.lilradish.lite.domain.failure.RefusalCode
import spock.lang.Specification

class VersionActSpec extends Specification {

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

    def "a version's state facts leave permission and authorship to the reader"() {
        expect:
        VersionAct.stateActs(standing) == acts as Set

        where:
        standing   || acts
        DRAFT      || [WRITE, SUBMIT]
        SUBMITTED  || [WITHDRAW, APPROVE]
        IN_SERVICE || [RETIRE]
        RETIRED    || []
    }

    def "a version's state facts cannot be widened"() {
        when:
        VersionAct.stateActs(SUBMITTED).add(WRITE)

        then:
        thrown(UnsupportedOperationException)
    }

    def "a version's state facts require a standing"() {
        when:
        VersionAct.stateActs(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "VersionAct standing must not be null"
    }
}
