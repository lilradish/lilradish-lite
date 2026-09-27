package org.lilradish.lite.testutil.codestep

import org.lilradish.lite.domain.codestep.CodeStep
import org.lilradish.lite.domain.codestep.CodeStepDeclaration
import org.lilradish.lite.domain.codestep.CodeStepName
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.wire.JsonValue

/**
 * A code step only specs hold, declaring what it is built with. Whatever it takes, it gives back one receipt, which
 * is all a spec running it asks of it. Its name's label is added to a spec's own database by the spec.
 */
final class SpecCodeStep implements CodeStep {

    /** Takes a reply to send, and gives back a receipt that stands; sending twice would send twice, so it may not. */
    static final SpecCodeStep SEND_REPLY = new SpecCodeStep("send_reply",
            [given("reply", new FieldShape.Text(2000))], [standing("receipt", new FieldShape.Text(64))], false)

    private final CodeStepName name

    private final CodeStepDeclaration declared

    SpecCodeStep(String name, List<Field> takes, List<Field> gives, boolean mayRunAgain) {
        this.name = new CodeStepName(name)
        this.declared = new CodeStepDeclaration(
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES), takes),
                new Declaration(DeclarationSide.GIVES, Demands.ofQuestion(DeclarationSide.GIVES), gives),
                mayRunAgain)
    }

    /** A field it takes that must be given. */
    static Field given(String name, FieldShape shape) {
        new Field(new FieldName(name), null, null, shape, new HowMany.One(), new Demand.Given(true))
    }

    /** A field it gives back that must be given and stands as it comes. */
    static Field standing(String name, FieldShape shape) {
        new Field(new FieldName(name), null, null, shape, new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null))
    }

    @Override
    CodeStepName name() {
        name
    }

    @Override
    CodeStepDeclaration declaration() {
        declared
    }

    @Override
    JsonValue.JsonObject run(JsonValue.JsonObject takes) {
        new JsonValue.JsonObject([new JsonValue.JsonMember("receipt", new JsonValue.JsonString("sent"))])
    }
}
