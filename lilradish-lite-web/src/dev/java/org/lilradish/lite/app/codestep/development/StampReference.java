package org.lilradish.lite.app.codestep.development;

import java.util.List;
import java.util.Locale;
import org.lilradish.lite.development.DevelopmentOnly;
import org.lilradish.lite.domain.codestep.CodeStep;
import org.lilradish.lite.domain.codestep.CodeStepDeclaration;
import org.lilradish.lite.domain.codestep.CodeStepName;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demand;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldLabel;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.declaration.HowMany;
import org.lilradish.lite.domain.wire.Digest;
import org.lilradish.lite.domain.wire.JsonValue;
import org.springframework.stereotype.Component;

/**
 * Stamps a complaint with a reference drawn from its words alone, so the same complaint is always stamped alike
 * and running it again changes nothing; it commits nothing outside this system.
 */
@DevelopmentOnly
@Component
public final class StampReference implements CodeStep {

    private static final CodeStepName NAME = new CodeStepName("stamp_reference");

    private static final String TAKEN = "complaint";

    private static final String GIVEN = "reference";

    private static final String PREFIX = "SUP-";

    private static final int DIGITS = 8;

    private static final CodeStepDeclaration DECLARED = new CodeStepDeclaration(
            new Declaration(
                    DeclarationSide.TAKES,
                    Demands.ofQuestion(DeclarationSide.TAKES),
                    List.of(new Field(
                            new FieldName(TAKEN),
                            new FieldLabel("The complaint"),
                            null,
                            new FieldShape.Text(4000),
                            new HowMany.One(),
                            new Demand.Given(true)))),
            new Declaration(
                    DeclarationSide.GIVES,
                    Demands.ofQuestion(DeclarationSide.GIVES),
                    List.of(new Field(
                            new FieldName(GIVEN),
                            new FieldLabel("Reference"),
                            null,
                            new FieldShape.Text(PREFIX.length() + DIGITS),
                            new HowMany.One(),
                            new Demand.Stands(true, FieldStanding.ALWAYS, null)))),
            true);

    @Override
    public CodeStepName name() {
        return NAME;
    }

    @Override
    public CodeStepDeclaration declaration() {
        return DECLARED;
    }

    @Override
    public JsonValue.JsonObject run(JsonValue.JsonObject takes) {
        for (JsonValue.JsonMember member : takes.members()) {
            if (member.name().equals(TAKEN) && member.value() instanceof JsonValue.JsonString complaint) {
                String reference = PREFIX
                        + Digest.sha256Hex(complaint.value())
                                .substring(0, DIGITS)
                                .toUpperCase(Locale.ROOT);
                return new JsonValue.JsonObject(
                        List.of(new JsonValue.JsonMember(GIVEN, new JsonValue.JsonString(reference))));
            }
        }
        throw new IllegalArgumentException(NAME.value() + " takes a complaint in text, and was given none");
    }
}
