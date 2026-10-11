package com.lilradish.lite.app.library

import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.file.FileExtension
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class DeclarationAnswersSpec extends Specification {

    def "file types survive at either depth while an unfinished limit and non-file properties stay absent"() {
        given:
        def file = { String name -> new Field(new FieldName(name), null, null,
                new FieldShape.File([new FileExtension(".png"), new FileExtension(".pdf")], 4096L),
                new HowMany.One(), new Demand.Given(false)) }
        def note = new Field(new FieldName("note"), null, null, new FieldShape.Text(200),
                new HowMany.One(), new Demand.Given(false))
        def nested = new Field(new FieldName("bundle"), null, null,
                new FieldShape.Nested([file("nested_file"), note]), new HowMany.One(), new Demand.Given(false))
        def unfinished = new Field(new FieldName("unfinished_file"), null, null,
                new FieldShape.File([new FileExtension(".txt"), new FileExtension(".md")], null),
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
        answer[0].get("types").collect { it.asString() } == [".png", ".pdf"]
        answer[0].get("maximumBytes").asLong() == 4096L
        answer[1].get("fields")[0].get("types").collect { it.asString() } == [".png", ".pdf"]
        answer[1].get("fields")[0].get("maximumBytes").asLong() == 4096L
        !answer[1].has("types")
        !answer[1].has("maximumBytes")
        !answer[1].get("fields")[1].has("types")
        !answer[1].get("fields")[1].has("maximumBytes")
        answer[2].get("types").collect { it.asString() } == [".txt", ".md"]
        !answer[2].has("maximumBytes")
    }
}
