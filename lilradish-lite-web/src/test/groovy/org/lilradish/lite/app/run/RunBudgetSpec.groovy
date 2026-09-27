package org.lilradish.lite.app.run

import spock.lang.Specification

class RunBudgetSpec extends Specification {

    def "what was spent is what was sent and what came back together"() {
        expect:
        new RunBudget.Spend(sent, cameBack, false, false).spent() == spent

        where:
        sent | cameBack || spent
        0    | 0        || 0
        100  | 40       || 140
    }

    def "two spends together add what each sent and got back, and are unknown where either is"() {
        expect:
        new RunBudget.Spend(100, 40, one, false).and(new RunBudget.Spend(30, 5, other, false)) ==
                new RunBudget.Spend(130, 45, unknown, false)

        where:
        one   | other || unknown
        false | false || false
        true  | false || true
        false | true  || true
        true  | true  || true
    }

    def "two spends together are measured here where either is, and counted by the model only where both are"() {
        expect:
        new RunBudget.Spend(100, 40, false, one).and(new RunBudget.Spend(30, 5, false, other)) ==
                new RunBudget.Spend(130, 45, false, measured)

        where:
        one   | other || measured
        false | false || false
        true  | false || true
        false | true  || true
        true  | true  || true
    }

    def "nothing spent adds nothing to what another spent"() {
        expect:
        RunBudget.Spend.NOTHING.and(new RunBudget.Spend(7, 3, true, true)) == new RunBudget.Spend(7, 3, true, true)
    }

    /** Past what a long counts the sum is refused rather than wrapped round into a small figure. */
    def "a spend past what can be counted is refused rather than wrapped round"() {
        when:
        new RunBudget.Spend(Long.MAX_VALUE, 0, false, false).and(new RunBudget.Spend(1, 0, false, false))

        then:
        thrown(ArithmeticException)
    }
}
