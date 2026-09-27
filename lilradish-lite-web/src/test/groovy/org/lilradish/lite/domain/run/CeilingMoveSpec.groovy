package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.CeilingMove.AWAITS_APPROVAL
import static org.lilradish.lite.domain.run.CeilingMove.HOLDS_AT_ONCE
import static org.lilradish.lite.domain.run.CeilingMove.UNCHANGED

import spock.lang.Specification

class CeilingMoveSpec extends Specification {

    /**
     * A raise or a taking away waits only where the version asks it to; anything else holds at once, a lowering
     * under what was already spent included.
     */
    def "what asking for a ceiling does turns on whether it raises the one in force and what the version asks"() {
        expect:
        CeilingMove.of(ceilingOf(inForce), ceilingOf(asked), needsApproval) == move

        where:
        inForce | asked | needsApproval || move
        100     | 100   | true          || UNCHANGED
        100     | 100   | false         || UNCHANGED
        null    | null  | true          || UNCHANGED
        100     | 101   | true          || AWAITS_APPROVAL
        100     | 101   | false         || HOLDS_AT_ONCE
        100     | null  | true          || AWAITS_APPROVAL
        100     | null  | false         || HOLDS_AT_ONCE
        100     | 99    | true          || HOLDS_AT_ONCE
        100     | 1     | true          || HOLDS_AT_ONCE
        null    | 100   | true          || HOLDS_AT_ONCE
        null    | 100   | false         || HOLDS_AT_ONCE
    }

    private static Ceiling ceilingOf(Integer count) {
        count == null ? null : new Ceiling(count)
    }
}
