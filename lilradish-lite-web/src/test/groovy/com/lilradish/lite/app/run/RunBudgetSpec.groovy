package com.lilradish.lite.app.run

import spock.lang.Specification

class RunBudgetSpec extends Specification {

    def "what was spent is what was sent and what came back together"() {
        expect:
        new RunBudget.Spend(sent, cameBack, false, 0, 0).spent() == spent

        where:
        sent | cameBack || spent
        0    | 0        || 0
        100  | 40       || 140
    }

    def "two spends together add what each sent and got back, and are unknown where either is"() {
        expect:
        new RunBudget.Spend(100, 40, one, 0, 0).and(new RunBudget.Spend(30, 5, other, 0, 0)) ==
                new RunBudget.Spend(130, 45, unknown, 0, 0)

        where:
        one   | other || unknown
        false | false || false
        true  | false || true
        false | true  || true
        true  | true  || true
    }

    def "combined spends count measured calls independently of their units"() {
        given:
        def first = new RunBudget.Spend(0, 0, false, one, 1)
        def second = new RunBudget.Spend(300, 5, false, other, 1)

        when:
        def together = first.and(second)

        then:
        together == new RunBudget.Spend(300, 5, false, one + other, 2)
        together.measuredHere()?.published() == measured

        where:
        one | other || measured
        0   | 0     || null
        1   | 0     || "partly"
        0   | 1     || "partly"
        1   | 1     || "wholly"
    }

    def "no measured calls omit how much was measured here, even when calls were counted"() {
        expect:
        new RunBudget.Spend(0, 0, false, 0, total).measuredHere() == null

        where:
        total << [0, 2]
    }

    def "measured calls cannot outnumber all calls or be negative"() {
        when:
        new RunBudget.Spend(0, 0, false, measured, total)

        then:
        thrown(IllegalArgumentException)

        where:
        measured | total
        -1       | 1
        1        | 0
        0        | -1
    }

    def "nothing spent adds nothing to what another spent"() {
        expect:
        RunBudget.Spend.NOTHING.and(new RunBudget.Spend(7, 3, true, 1, 1)) == new RunBudget.Spend(7, 3, true, 1, 1)
    }

    /** Past what a long counts the sum is refused rather than wrapped round into a small figure. */
    def "a spend past what can be counted is refused rather than wrapped round"() {
        when:
        new RunBudget.Spend(Long.MAX_VALUE, 0, false, 0, 0).and(new RunBudget.Spend(1, 0, false, 0, 0))

        then:
        thrown(ArithmeticException)
    }
}
