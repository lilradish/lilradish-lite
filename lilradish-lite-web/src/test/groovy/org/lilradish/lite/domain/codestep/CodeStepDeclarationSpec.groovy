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
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class CodeStepDeclarationSpec extends Specification {

    static final Declaration TAKES = new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES),
            [new Field(new FieldName("reply"), null, null, new FieldShape.Text(2000), new HowMany.One(), new Demand.Given(true))])

    static final Declaration GIVES = new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES),
            [new Field(new FieldName("receipt"), null, null, new FieldShape.Text(64), new HowMany.One(),
                    new Demand.Stands(true, FieldStanding.ALWAYS, null))])

    static final EntryVersionId SILVER = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000c001"))

    static final EntryVersionId GOLD = new EntryVersionId(UUID.fromString("00000000-0000-4000-8000-00000000c002"))

    /** What it gives back stands as a question's does, so a workflow's demands, standing nowhere, are not what it asks. */
    def "refuses a half on the wrong side, or asking other than a question's half asks"() {
        when:
        new CodeStepDeclaration(takes, gives, false)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == expectedMessage

        where:
        takes                                                                                  | gives                                                                                   || expectedMessage
        GIVES                                                                                  | GIVES                                                                                   || "CodeStepDeclaration takes must be that half, asking what a question's asks, not gives asking Demands[first=STANDS, below=GIVEN]"
        TAKES                                                                                  | TAKES                                                                                   || "CodeStepDeclaration gives must be that half, asking what a question's asks, not takes asking Demands[first=GIVEN, below=GIVEN]"
        TAKES                                                                                  | new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), []) || "CodeStepDeclaration gives must be that half, asking what a question's asks, not gives asking Demands[first=GIVEN, below=GIVEN]"
    }

    def "refuses a half it is not given"() {
        when:
        new CodeStepDeclaration(takes, gives, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        takes | gives || expectedMessage
        null  | GIVES || "CodeStepDeclaration takes must not be null"
        TAKES | null  || "CodeStepDeclaration gives must not be null"
    }

    /** A term not yet pinned to a list is a draft's, and pins nothing. */
    def "answers every list a term pins at any level, those it takes first, each once and in declared order"() {
        given:
        def declared = new CodeStepDeclaration(
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), takes),
                new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), gives), false)

        expect:
        declared.lists() as List == expected

        where:
        takes                                    | gives                                                              || expected
        [termIn("tier", SILVER)]                 | [standing("rank", new FieldShape.Nested([termIn("grade", GOLD)]))] || [SILVER, GOLD]
        [nested("line", [termIn("tier", GOLD)])] | [standingTerm("rank", SILVER), standingTerm("band", GOLD)]         || [GOLD, SILVER]
        [termIn("tier", null)]                   | [standingTerm("rank", SILVER)]                                     || [SILVER]
        TAKES.fields()                           | GIVES.fields()                                                     || []
    }

    private static Field termIn(String name, EntryVersionId list) {
        new Field(new FieldName(name), null, null, new FieldShape.Term(list), new HowMany.One(), new Demand.Given(true))
    }

    private static Field nested(String name, List<Field> fields) {
        new Field(new FieldName(name), null, null, new FieldShape.Nested(fields), new HowMany.One(), new Demand.Given(true))
    }

    private static Field standing(String name, FieldShape shape) {
        new Field(new FieldName(name), null, null, shape, new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null))
    }

    private static Field standingTerm(String name, EntryVersionId list) {
        standing(name, new FieldShape.Term(list))
    }
}
