package com.lilradish.lite.app.filling

import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.TextLines
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.filling.FillField
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

class FillFieldAnswerSpec extends Specification {

    def "a text field tells the reader its declared lines and keeps several when omitted"() {
        given:
        def one = new FillField(new FieldName("title"), null, null, FieldKind.TEXT, 30, null, false,
                null, [], null, null, TextLines.ONE)
        def several = new FillField(new FieldName("details"), null, null, FieldKind.TEXT, 60, null, false, null, [])

        when:
        def written = JsonMapper.builder().build().valueToTree(FillFieldAnswer.of([one, several]))

        then:
        written[0].path("lines").asString() == "one"
        written[1].path("lines").asString() == "several"
        !written[0].has("types")
        !written[1].has("fields")
    }

    def "a file field reaches the reader with ordered normalized types and both limits"() {
        given:
        def field = new FillField(new FieldName("attachments"), null, null, FieldKind.FILE, null, 3, true,
                null, [], [new FileExtension(".PNG"), new FileExtension(".pdf")], 1_048_576L)

        when:
        def written = JsonMapper.builder().build().valueToTree(FillFieldAnswer.of([field])[0])

        then:
        written.path("name").asString() == "attachments"
        written.path("kind").asString() == "file"
        written.path("types").isArray()
        written.path("types").toString() == '[".png",".pdf"]'
        written.path("maximumBytes").longValue() == 1_048_576L
        written.path("most").intValue() == 3
        !written.has("longest") && !written.has("terms") && !written.has("fields")
    }

    def "nested and repeated text fields publish only their declared line count"() {
        given:
        def title = new FillField(new FieldName("title"), null, null, FieldKind.TEXT, 80, null, true,
                null, [], null, null, TextLines.ONE)
        def notes = new FillField(new FieldName("notes"), null, null, FieldKind.TEXT, 400, 3, false,
                null, [], null, null, TextLines.SEVERAL)
        def record = new FillField(new FieldName("record"), null, null, FieldKind.FIELDS, null, null, true,
                null, [title, notes])

        when:
        def written = JsonMapper.builder().build().valueToTree(FillFieldAnswer.of([record]))

        then:
        written[0].path("fields")[0].path("lines").asString() == "one"
        written[0].path("fields")[1].path("lines").asString() == "several"
        written[0].path("fields")[1].path("most").intValue() == 3
        !written[0].has("lines")
        !written.toString().contains("markdown")
        !written.toString().contains("author")
    }
}
