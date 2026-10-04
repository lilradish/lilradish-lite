package org.lilradish.lite.domain.workflow

import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

class SearchQuestionPartSpec extends Specification {

    static final UUID STEP = UUID.fromString("0000000c-0000-4000-8000-000000000101")

    static final UUID PART = UUID.fromString("00000020-0000-4000-8000-000000000101")

    static final BindingSource WORKFLOW_INPUT = new BindingSource.WorkflowInput(Pointer.parse("customer.name"))

    static final BindingSource STEP_OUTPUT = new BindingSource.StepOutput(STEP, Pointer.parse("order"))

    static final BindingSource WRITTEN = new BindingSource.Written(new JsonValue.JsonString("Demo Sample"))

    def "written text is kept exactly as written, whitespace alone included"() {
        when:
        def literal = new SearchQuestionPart.LiteralPart(text)

        then:
        literal.text() == text

        where:
        text << ["Demo Sample", " ", "  and  ", "a" * 8192, "one\ntwo", "one\ttwo"]
    }

    /** Only a class the logging convention matches drops what it marks, so the name is part of the protection. */
    def "written text never shows where the part is rendered for a log"() {
        when:
        def rendered = new SearchQuestionPart.LiteralPart("Demo Sample").toString()

        then:
        rendered == 'SearchQuestionPart$LiteralPart()'
        !rendered.contains("Demo Sample")
    }

    def "written text is refused where it is missing or empty"() {
        when:
        new SearchQuestionPart.LiteralPart(text)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        text || expectedException        | expectedMessage
        null || NullPointerException     | "SearchQuestionPart.LiteralPart text must not be null"
        ""   || IllegalArgumentException | "SearchQuestionPart.LiteralPart text must not be empty"
    }

    def "written text longer than one piece or holding forbidden prose is refused"() {
        when:
        new SearchQuestionPart.LiteralPart(text)

        then:
        thrown(IllegalArgumentException)

        where:
        text << ["a" * 8193, "a" + Character.toString(7), "a" + Character.toString(0x202E),
                 "a" + Character.toString(0xE0041), "a" + Character.toString(0xD800)]
    }

    def "a read takes a value of this workflow's own inputs or of an earlier step, under the key it is stored by"() {
        when:
        def read = new SearchQuestionPart.Read(PART, source)

        then:
        noExceptionThrown()
        read.id() == PART
        read.source() == source

        where:
        source << [WORKFLOW_INPUT, STEP_OUTPUT]
    }

    def "a read is refused where its key or its source is missing, or where its source is written text"() {
        when:
        new SearchQuestionPart.Read(id, source)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        id   | source         || expectedException        | expectedMessage
        null | WORKFLOW_INPUT || NullPointerException     | "SearchQuestionPart.Read id must not be null"
        PART | null           || NullPointerException     | "SearchQuestionPart.Read source must not be null"
        PART | WRITTEN        || IllegalArgumentException | "SearchQuestionPart.Read source must be read, not written; written text is a literal"
    }
}
