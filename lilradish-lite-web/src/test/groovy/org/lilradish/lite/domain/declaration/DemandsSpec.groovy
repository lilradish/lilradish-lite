package org.lilradish.lite.domain.declaration

import static org.lilradish.lite.domain.declaration.Demands.Kind.GIVEN
import static org.lilradish.lite.domain.declaration.Demands.Kind.STANDS

import spock.lang.Specification

class DemandsSpec extends Specification {

    def "a question's halves are asked as the story lays them down"() {
        expect:
        Demands.ofQuestion(DeclarationSide.TAKES) == new Demands(GIVEN, GIVEN)
        Demands.ofQuestion(DeclarationSide.GIVES) == new Demands(STANDS, GIVEN)
    }

    /** What a workflow or a route gives back stood where it was made, so nothing of it is asked to stand again. */
    def "a workflow's halves ask every field only whether it must be given"() {
        expect:
        Demands.ofWorkflow(DeclarationSide.TAKES) == new Demands(GIVEN, GIVEN)
        Demands.ofWorkflow(DeclarationSide.GIVES) == new Demands(GIVEN, GIVEN)

        and: "so it differs from a question's only where a question's value stands"
        Demands.ofWorkflow(DeclarationSide.GIVES) != Demands.ofQuestion(DeclarationSide.GIVES)
    }

    def "a host's demands are asked of a side"() {
        when:
        host == "workflow" ? Demands.ofWorkflow(null) : Demands.ofQuestion(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Demands side must not be null"

        where:
        host << ["workflow", "question"]
    }

    def "what a depth asks is its level's, and admits exactly the demand of that kind"() {
        given:
        def demands = new Demands(STANDS, GIVEN)

        expect:
        demands.at(true) == STANDS
        demands.at(false) == GIVEN
        demands.admits(new Demand.Stands(true, null, null), true)
        !demands.admits(new Demand.Given(true), true)
        demands.admits(new Demand.Given(false), false)
        !demands.admits(new Demand.Stands(false, null, null), false)
    }

    def "each demand is of its own kind"() {
        expect:
        Demands.kindOf(demand) == kind

        where:
        demand                               || kind
        new Demand.Given(false)              || GIVEN
        new Demand.Stands(true, null, null)  || STANDS
    }

    /** What a depth does not ask is never read, whatever was stored or sent for it. */
    def "a demand is built as its depth asks it, from only what that depth reads"() {
        expect:
        new Demands(first, below).demandAt(atFirst, false, FieldStanding.ABOVE_CONFIDENCE, 80) == built

        where:
        first  | below | atFirst || built
        GIVEN  | GIVEN | true    || new Demand.Given(false)
        GIVEN  | GIVEN | false   || new Demand.Given(false)
        STANDS | GIVEN | true    || new Demand.Stands(false, FieldStanding.ABOVE_CONFIDENCE, 80)
        STANDS | GIVEN | false   || new Demand.Given(false)
    }

    def "a field is refused where nothing says whether it must be given, at every depth and on either side"() {
        when:
        demands.demandAt(atFirst, null, FieldStanding.NEVER, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Demands a field says whether it must be given"

        where:
        demands                                   | atFirst
        Demands.ofQuestion(DeclarationSide.TAKES) | true
        Demands.ofQuestion(DeclarationSide.GIVES) | true
        Demands.ofQuestion(DeclarationSide.GIVES) | false
    }

    def "nothing a field holds stands, and each level says what it asks"() {
        when:
        new Demands(first, below)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        first  | below  || expectedException        | expectedMessage
        STANDS | STANDS || IllegalArgumentException | "Demands let only a field no other holds stand"
        GIVEN  | STANDS || IllegalArgumentException | "Demands let only a field no other holds stand"
        null   | GIVEN  || NullPointerException     | "Demands first must not be null"
        STANDS | null   || NullPointerException     | "Demands below must not be null"
    }
}
