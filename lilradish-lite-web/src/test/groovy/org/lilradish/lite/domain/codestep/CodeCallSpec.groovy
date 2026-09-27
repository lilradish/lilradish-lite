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
import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

class CodeCallSpec extends Specification {

    static final CodeStepName ROUTE = new CodeStepName("route")

    static final EntryVersionId TIERS = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000e001"))

    static final EntryVersionId REGIONS = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000e002"))

    static final OfferedTerms TERMS = new OfferedTerms([new OfferedTerms.Offered(new Term("gold"), new TermMeaning("Gold"))], null)

    static final JsonValue.JsonObject TAKES = new JsonValue.JsonObject(
            [new JsonValue.JsonMember("tier", new JsonValue.JsonString("gold"))])

    /** Takes a tier from one list, and gives back a region from another. */
    static final CodeStepDeclaration DECLARED = new CodeStepDeclaration(
            new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES),
                    [new Field(new FieldName("tier"), null, null, new FieldShape.Term(TIERS), new HowMany.One(),
                            new Demand.Given(true))]),
            new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES),
                    [new Field(new FieldName("region"), null, null, new FieldShape.Term(REGIONS), new HowMany.One(),
                            new Demand.Stands(true, FieldStanding.ALWAYS, null))]),
            false)

    def "keeps the terms of every list its declaration pins, whatever is done to the map they came in afterwards"() {
        given:
        def handed = [(TIERS): TERMS, (REGIONS): TERMS]
        def call = new CodeCall(ROUTE, DECLARED, handed, TAKES)

        when:
        handed.remove(REGIONS)

        then:
        call.lists() == [(TIERS): TERMS, (REGIONS): TERMS]
        call.name() == ROUTE
        call.takes() == TAKES
    }

    /** What it gives back is read against these terms, so a call without them could not say whether it fits. */
    def "refuses a call handed no terms for a list its declaration pins, naming the code step and the list"() {
        when:
        new CodeCall(ROUTE, DECLARED, lists, TAKES)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "CodeCall to route is handed no terms for list " + missing.value()

        where:
        lists              || missing
        [:]                || TIERS
        [(TIERS): TERMS]   || REGIONS
        [(REGIONS): TERMS] || TIERS
    }

    def "refuses a part it is not given"() {
        when:
        new CodeCall(name, declared, lists, takes)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        name  | declared | lists                              | takes || expectedMessage
        null  | DECLARED | [(TIERS): TERMS, (REGIONS): TERMS] | TAKES || "CodeCall name must not be null"
        ROUTE | null     | [(TIERS): TERMS, (REGIONS): TERMS] | TAKES || "CodeCall declared must not be null"
        ROUTE | DECLARED | null                               | TAKES || "CodeCall lists must not be null"
        ROUTE | DECLARED | [(TIERS): TERMS, (REGIONS): TERMS] | null  || "CodeCall takes must not be null"
    }
}
