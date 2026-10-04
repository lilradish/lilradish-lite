package org.lilradish.lite.app.library

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.file.MediaTypeName
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class DeclarationAnswersSpec extends Specification {

    def "file types survive at either depth while an unfinished limit and non-file properties stay absent"() {
        given:
        def file = { String name -> new Field(new FieldName(name), null, null,
                new FieldShape.File([new MediaTypeName("image/png"), new MediaTypeName("application/pdf")], 4096L),
                new HowMany.One(), new Demand.Given(false)) }
        def note = new Field(new FieldName("note"), null, null, new FieldShape.Text(200),
                new HowMany.One(), new Demand.Given(false))
        def nested = new Field(new FieldName("bundle"), null, null,
                new FieldShape.Nested([file("nested_file"), note]), new HowMany.One(), new Demand.Given(false))
        def unfinished = new Field(new FieldName("unfinished_file"), null, null,
                new FieldShape.File([new MediaTypeName("text/plain")], null),
                new HowMany.One(), new Demand.Given(false))
        def half = new StoredDeclarations.Half(
                new Declaration(DeclarationSide.TAKES, Demands.ofQuestion(DeclarationSide.TAKES),
                        [file("top_file"), nested, unfinished]),
                [new StoredDeclarations.Keyed(UUID.randomUUID(), []),
                 new StoredDeclarations.Keyed(UUID.randomUUID(), [
                         new StoredDeclarations.Keyed(UUID.randomUUID(), []),
                         new StoredDeclarations.Keyed(UUID.randomUUID(), [])]),
                 new StoredDeclarations.Keyed(UUID.randomUUID(), [])])

        when:
        def answer = JsonMapper.builder().build().valueToTree(DeclarationAnswers.of(half, [:]))

        then:
        answer[0].get("types").collect { it.asString() } == ["image/png", "application/pdf"]
        answer[0].get("maximumBytes").asLong() == 4096L
        answer[1].get("fields")[0].get("types").collect { it.asString() } == ["image/png", "application/pdf"]
        answer[1].get("fields")[0].get("maximumBytes").asLong() == 4096L
        !answer[1].has("types")
        !answer[1].has("maximumBytes")
        !answer[1].get("fields")[1].has("types")
        !answer[1].get("fields")[1].has("maximumBytes")
        answer[2].get("types").collect { it.asString() } == ["text/plain"]
        !answer[2].has("maximumBytes")
    }
}
