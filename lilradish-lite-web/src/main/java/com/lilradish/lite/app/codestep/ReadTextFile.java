package com.lilradish.lite.app.codestep;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.codestep.CodeStep;
import com.lilradish.lite.domain.codestep.CodeStepDeclaration;
import com.lilradish.lite.domain.codestep.CodeStepName;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.Demand;
import com.lilradish.lite.domain.declaration.Demands;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldHelp;
import com.lilradish.lite.domain.declaration.FieldLabel;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.declaration.FieldStanding;
import com.lilradish.lite.domain.declaration.HowMany;
import com.lilradish.lite.domain.declaration.TextLines;
import com.lilradish.lite.domain.file.FileExtension;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.filling.FillPath;
import com.lilradish.lite.domain.wire.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Returns each file's bounded prefix in input order, preserving its text and counting code points.
 * Changes nothing outside the run, so the same value may be run again.
 */
@Component
public final class ReadTextFile implements CodeStep {

    private static final CodeStepName NAME = new CodeStepName("read_text_file");

    private static final FieldName FILE = new FieldName("file");

    private static final int MOST_TEXT = 262_144;

    private static final CodeStepDeclaration DECLARED = new CodeStepDeclaration(
            new Declaration(
                    DeclarationSide.TAKES,
                    Demands.ofQuestion(DeclarationSide.TAKES),
                    List.of(new Field(
                            FILE,
                            new FieldLabel("Text files"),
                            new FieldHelp("Plain-text files to read: .txt, .md, .csv or .json, each up to 2 MiB."),
                            new FieldShape.File(
                                    List.of(
                                            new FileExtension(".txt"),
                                            new FileExtension(".md"),
                                            new FileExtension(".csv"),
                                            new FileExtension(".json")),
                                    2_097_152L),
                            new HowMany.Many(3),
                            new Demand.Given(true)))),
            new Declaration(
                    DeclarationSide.GIVES,
                    Demands.ofQuestion(DeclarationSide.GIVES),
                    List.of(new Field(
                            new FieldName("text"),
                            new FieldLabel("Text from the files"),
                            new FieldHelp(
                                    "The first 262,144 characters of each file, exactly as written. Anything after that is left out."),
                            new FieldShape.Text(MOST_TEXT, TextLines.SEVERAL),
                            new HowMany.Many(3),
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
    public Output run(Input takes) {
        JsonValue files = takes.values().members().stream()
                .filter(member -> member.name().equals(FILE.value()))
                .findFirst()
                .orElseThrow()
                .value();
        List<JsonValue> texts = new ArrayList<>();
        List<JsonValue> items = ((JsonValue.JsonArray) files).items();
        for (int index = 0; index < items.size(); index++) {
            StoredFile file = StoredFile.fromKept(items.get(index));
            FillPath path = new FillPath(List.of(new FillPath.Named(FILE), new FillPath.Place(index)));
            try (InputStream input = requireNonNull(takes.files().get(path)).open()) {
                String text = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(input.readAllBytes()))
                        .toString();
                String beginning = text.codePointCount(0, text.length()) <= MOST_TEXT
                        ? text
                        : text.substring(0, text.offsetByCodePoints(0, MOST_TEXT));
                texts.add(new JsonValue.JsonString(beginning));
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException(
                        file.name().value()
                                + " isn't plain text, so it can't be read. Use .txt, .md, .csv or .json files saved as plain text (UTF-8).",
                        invalid);
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }
        return Output.plain(
                new JsonValue.JsonObject(List.of(new JsonValue.JsonMember("text", new JsonValue.JsonArray(texts)))));
    }
}
