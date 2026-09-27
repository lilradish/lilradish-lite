package org.lilradish.lite.domain.codestep

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class ReleasedCodeStepSpec extends Specification {

    static final EntryVersionId TIERS = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000d001"))

    static final EntryVersionId REGIONS = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000d002"))

    static final EntryVersionId ELSEWHERE = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000d003"))

    static final OfferedTerms TERMS = new OfferedTerms([new OfferedTerms.Offered(new Term("gold"), new TermMeaning("Gold"))], null)

    /** Takes a tier, and gives back a region beneath a routing it gives back. */
    static final CodeStepDeclaration ROUTES = declared(true)

    def "refuses a declaration or lists it is not given"() {
        when:
        new ReleasedCodeStep(declaredGiven, lists)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        declaredGiven | lists || expectedMessage
        null          | [:]   || "ReleasedCodeStep declared must not be null"
        ROUTES        | null  || "ReleasedCodeStep lists must not be null"
    }

    def "keeps the lists as they were handed over, whatever is done to the map they came in afterwards"() {
        given:
        def handed = [(TIERS): TERMS]
        def released = new ReleasedCodeStep(ROUTES, handed)

        when:
        handed.put(REGIONS, TERMS)

        then:
        released.lists() == [(TIERS): TERMS]
        !released.lists().containsKey(REGIONS)
    }

    def "may run again exactly where the release declares it may"() {
        expect:
        new ReleasedCodeStep(declared(mayRunAgain), [:]).mayRunAgain() == mayRunAgain

        where:
        mayRunAgain << [true, false]
    }

    /** A list another group owns, or one not here at all, is simply not among the lists it is handed. */
    def "answers every list it pins that it is not handed, in declared order, and none it is handed or does not pin"() {
        expect:
        new ReleasedCodeStep(ROUTES, lists).listsMissing() as List == missing

        where:
        lists                                                  || missing
        [:]                                                    || [TIERS, REGIONS]
        [(TIERS): TERMS]                                       || [REGIONS]
        [(REGIONS): TERMS]                                     || [TIERS]
        [(TIERS): TERMS, (REGIONS): TERMS]                     || []
        [(TIERS): TERMS, (REGIONS): TERMS, (ELSEWHERE): TERMS] || []
        [(ELSEWHERE): TERMS]                                   || [TIERS, REGIONS]
    }

    private static CodeStepDeclaration declared(boolean mayRunAgain) {
        def region = new Field(new FieldName("region"), null, null, new FieldShape.Term(REGIONS), new HowMany.One(),
                new Demand.Given(true))
        new CodeStepDeclaration(
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES),
                        [new Field(new FieldName("tier"), null, null, new FieldShape.Term(TIERS), new HowMany.One(),
                                new Demand.Given(true))]),
                new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES),
                        [new Field(new FieldName("routing"), null, null, new FieldShape.Nested([region]), new HowMany.One(),
                                new Demand.Stands(true, FieldStanding.ALWAYS, null))]),
                mayRunAgain)
    }
}
