package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.identity.GroupPermission.APPROVE_ENTRY
import static org.lilradish.lite.domain.identity.GroupPermission.READ_ALL_RUNS
import static org.lilradish.lite.domain.identity.GroupPermission.START_RUN
import static org.lilradish.lite.domain.run.RunAct.APPROVE_RAISE
import static org.lilradish.lite.domain.run.RunAct.CHANGE_CEILING
import static org.lilradish.lite.domain.run.RunAct.OPEN_AGAIN
import static org.lilradish.lite.domain.run.RunAct.REFUSE_RAISE
import static org.lilradish.lite.domain.run.RunAct.RENAME
import static org.lilradish.lite.domain.run.RunAct.STOP
import static org.lilradish.lite.domain.run.RunAct.WITHDRAW_RAISE
import static org.lilradish.lite.domain.run.RunPosition.Raise.ASKED_BY_ANOTHER
import static org.lilradish.lite.domain.run.RunPosition.Raise.ASKED_BY_READER
import static org.lilradish.lite.domain.run.RunPosition.Raise.NONE_WAITING

import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.identity.GroupPermission
import spock.lang.Specification

class RunActSpec extends Specification {

    static final Set<GroupPermission> EVERYTHING = EnumSet.allOf(GroupPermission)

    /** Deciding a raise is approving an entry's kind of authority; everything else is the starter's. */
    def "each act on a run takes the one permission the group's roles reach it by, and there are no others"() {
        expect:
        RunAct.values().collectEntries { [(it): it.permission()] } == [
                (STOP)          : START_RUN,
                (OPEN_AGAIN)    : START_RUN,
                (RENAME)        : START_RUN,
                (CHANGE_CEILING): START_RUN,
                (APPROVE_RAISE) : APPROVE_ENTRY,
                (REFUSE_RAISE)  : APPROVE_ENTRY,
                (WITHDRAW_RAISE): START_RUN,
        ]
    }

    def "each act on a run is published under the spelling a reader draws its control by"() {
        expect:
        RunAct.values().collectEntries { [(it): it.published()] } == [
                (STOP)          : "stop",
                (OPEN_AGAIN)    : "open_again",
                (RENAME)        : "rename",
                (CHANGE_CEILING): "change_ceiling",
                (APPROVE_RAISE) : "approve_raise",
                (REFUSE_RAISE)  : "refuse_raise",
                (WITHDRAW_RAISE): "withdraw_raise",
        ]
    }

    /**
     * Stopped or not, done or not, and whatever raise waits, a run beneath another is changed only with the run at
     * the top; that a done run leaves nothing to stop is what it offers, not what an act on it refuses.
     */
    def "an act on the run itself is refused beneath another run, and nowhere else"() {
        expect:
        act.refusal(new RunPosition(atTop, stopped, done, raise)) == refusal

        where:
        act            | atTop | stopped | done  | raise            || refusal
        STOP           | true  | false   | false | NONE_WAITING     || null
        STOP           | false | false   | false | NONE_WAITING     || RefusalCode.RUN_BENEATH_ANOTHER
        STOP           | true  | false   | true  | NONE_WAITING     || null
        STOP           | false | true    | true  | NONE_WAITING     || RefusalCode.RUN_BENEATH_ANOTHER
        OPEN_AGAIN     | true  | true    | false | ASKED_BY_READER  || null
        OPEN_AGAIN     | false | true    | false | NONE_WAITING     || RefusalCode.RUN_BENEATH_ANOTHER
        OPEN_AGAIN     | true  | true    | true  | NONE_WAITING     || null
        RENAME         | true  | true    | false | ASKED_BY_ANOTHER || null
        RENAME         | false | false   | false | ASKED_BY_READER  || RefusalCode.RUN_BENEATH_ANOTHER
        RENAME         | true  | false   | true  | NONE_WAITING     || null
        CHANGE_CEILING | true  | true    | false | ASKED_BY_READER  || null
        CHANGE_CEILING | false | true    | false | ASKED_BY_ANOTHER || RefusalCode.RUN_BENEATH_ANOTHER
        CHANGE_CEILING | false | false   | true  | NONE_WAITING     || RefusalCode.RUN_BENEATH_ANOTHER
    }

    /** Whoever asked may only withdraw it, anybody else only decide it, and stopped or done changes neither. */
    def "an act on a raise is refused for how its reader stands to the raise, and for nothing else"() {
        expect:
        act.refusal(new RunPosition(true, stopped, done, raise)) == refusal

        where:
        act            | stopped | done  | raise            || refusal
        APPROVE_RAISE  | false   | false | NONE_WAITING     || RefusalCode.CEILING_RAISE_NOT_WAITING
        APPROVE_RAISE  | true    | false | ASKED_BY_READER  || RefusalCode.CEILING_RAISE_ASKED_BY_CALLER
        APPROVE_RAISE  | true    | false | ASKED_BY_ANOTHER || null
        APPROVE_RAISE  | false   | true  | ASKED_BY_ANOTHER || null
        REFUSE_RAISE   | true    | false | NONE_WAITING     || RefusalCode.CEILING_RAISE_NOT_WAITING
        REFUSE_RAISE   | false   | false | ASKED_BY_READER  || RefusalCode.CEILING_RAISE_ASKED_BY_CALLER
        REFUSE_RAISE   | false   | false | ASKED_BY_ANOTHER || null
        REFUSE_RAISE   | false   | true  | ASKED_BY_READER  || RefusalCode.CEILING_RAISE_ASKED_BY_CALLER
        WITHDRAW_RAISE | false   | false | NONE_WAITING     || RefusalCode.CEILING_RAISE_NOT_WAITING
        WITHDRAW_RAISE | true    | false | ASKED_BY_READER  || null
        WITHDRAW_RAISE | false   | false | ASKED_BY_ANOTHER || RefusalCode.CEILING_RAISE_ASKED_BY_ANOTHER
        WITHDRAW_RAISE | true    | true  | NONE_WAITING     || RefusalCode.CEILING_RAISE_NOT_WAITING
    }

    def "an act cannot be judged against nowhere"() {
        when:
        STOP.refusal(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "RunAct position must not be null"
    }

    /**
     * A stopped run offers opening again and never stopping, and its name and ceiling still; a done run offers
     * neither, stopped or not; a raise waiting offers deciding it to anybody but whoever asked, who is offered
     * withdrawing it instead.
     */
    def "what a run offers somebody holding everything turns on where it stands"() {
        expect:
        RunAct.admitted(EVERYTHING, new RunPosition(atTop, stopped, done, raise)) == admitted as Set

        where:
        atTop | stopped | done  | raise            || admitted
        true  | false   | false | NONE_WAITING     || [STOP, RENAME, CHANGE_CEILING]
        true  | true    | false | NONE_WAITING     || [OPEN_AGAIN, RENAME, CHANGE_CEILING]
        true  | false   | false | ASKED_BY_ANOTHER || [STOP, RENAME, CHANGE_CEILING, APPROVE_RAISE, REFUSE_RAISE]
        true  | true    | false | ASKED_BY_ANOTHER || [OPEN_AGAIN, RENAME, CHANGE_CEILING, APPROVE_RAISE, REFUSE_RAISE]
        true  | false   | false | ASKED_BY_READER  || [STOP, RENAME, CHANGE_CEILING, WITHDRAW_RAISE]
        false | false   | false | NONE_WAITING     || []
        false | true    | false | NONE_WAITING     || []
        true  | false   | true  | NONE_WAITING     || [RENAME, CHANGE_CEILING]
        true  | true    | true  | NONE_WAITING     || [RENAME, CHANGE_CEILING]
        true  | false   | true  | ASKED_BY_ANOTHER || [RENAME, CHANGE_CEILING, APPROVE_RAISE, REFUSE_RAISE]
        true  | true    | true  | ASKED_BY_READER  || [RENAME, CHANGE_CEILING, WITHDRAW_RAISE]
        false | true    | true  | NONE_WAITING     || []
    }

    def "an act whose permission is not held is never offered, wherever the run stands"() {
        expect:
        RunAct.admitted(permitted as Set<GroupPermission>, new RunPosition(true, false, false, raise)) == admitted as Set

        where:
        permitted       | raise            || admitted
        [START_RUN]     | ASKED_BY_ANOTHER || [STOP, RENAME, CHANGE_CEILING]
        [APPROVE_ENTRY] | ASKED_BY_ANOTHER || [APPROVE_RAISE, REFUSE_RAISE]
        [APPROVE_ENTRY] | ASKED_BY_READER  || []
        [READ_ALL_RUNS] | ASKED_BY_ANOTHER || []
        []              | NONE_WAITING     || []
    }

    def "what a run offers cannot be asked of no permissions"() {
        when:
        RunAct.admitted(null, new RunPosition(true, false, false, NONE_WAITING))

        then:
        def refused = thrown(NullPointerException)
        refused.message == "RunAct permitted must not be null"
    }

    def "what a run offers is nobody's to widen"() {
        given:
        def offered = RunAct.admitted(EVERYTHING, new RunPosition(true, false, false, NONE_WAITING))

        when:
        offered.add(OPEN_AGAIN)

        then:
        thrown(UnsupportedOperationException)
        !offered.contains(OPEN_AGAIN)
    }
}
