package com.lilradish.lite.domain.declaration

import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class FieldShapeSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101"))

    static final Field HELD = new Field(new FieldName("product"), null, null, new FieldShape.Text(128),
            new HowMany.One(), new Demand.Given(true))

    def "text with no lines chosen keeps accepting several lines, while one rejects every line break"() {
        expect:
        new FieldShape.Text(30).lines() == TextLines.SEVERAL
        new FieldShape.Text(null, TextLines.ONE).lines() == TextLines.ONE
        TextLines.values()*.published() == ["one", "several"]
        TextLines.SEVERAL.accepts("Demo Sample" + Character.toString(codePoint))
        !TextLines.ONE.accepts("Demo Sample" + Character.toString(codePoint))
        !TextLines.ONE.accepts(Character.toString(codePoint))
        TextLines.ONE.accepts("Demo Sample\t")

        where:
        codePoint << [0x0D, 0x0A, 0x0B, 0x0C, 0x85, 0x2028, 0x2029]
    }

    def "a text shape must say one or several lines"() {
        when:
        new FieldShape.Text(30, null)

        then:
        thrown(NullPointerException)
    }

    def "each shape says the kind it is, and what it carries of that kind"() {
        expect:
        shape.kind() == kind

        where:
        shape                                  || kind
        new FieldShape.Text(4000)              || FieldKind.TEXT
        new FieldShape.Text(null)              || FieldKind.TEXT
        new FieldShape.Plain(FieldKind.NUMBER) || FieldKind.NUMBER
        new FieldShape.Plain(FieldKind.DATE)   || FieldKind.DATE
        new FieldShape.Plain(FieldKind.MOMENT) || FieldKind.MOMENT
        new FieldShape.Plain(FieldKind.YES_NO) || FieldKind.YES_NO
        new FieldShape.Term(LIST)              || FieldKind.TERM
        new FieldShape.Term(null)              || FieldKind.TERM
        new FieldShape.Nested([HELD])          || FieldKind.FIELDS
    }

    /** One is the shortest a text may be declared; none is what a draft has not chosen yet. */
    def "text is as long as it says, from one up, or says nothing yet"() {
        expect:
        new FieldShape.Text(longest).longest() == longest

        where:
        longest << [1, 4000, Integer.MAX_VALUE, null]
    }

    def "text said to be shorter than one character is refused"() {
        when:
        new FieldShape.Text(longest)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FieldShape.Text longest must be at least one: " + longest

        where:
        longest << [0, -1, Integer.MIN_VALUE]
    }

    /** Those three each say more of themselves than a plain kind can carry. */
    def "a plain shape is refused for a kind that carries more"() {
        when:
        new FieldShape.Plain(kind)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "FieldShape.Plain cannot be " + kind + ", which says more of itself"

        where:
        kind << [FieldKind.TEXT, FieldKind.TERM, FieldKind.FIELDS]
    }

    def "a plain shape names its kind"() {
        when:
        new FieldShape.Plain(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "FieldShape.Plain kind must not be null"
    }

    /** Held as a copy, so a list changed after it was handed over changes nothing held. */
    def "fields of their own are held in the order given, and apart from the list they were handed in"() {
        given:
        def handed = [HELD]

        when:
        def nested = new FieldShape.Nested(handed)
        handed.add(HELD)

        then:
        nested.fields() == [HELD]

        when:
        nested.fields().add(HELD)

        then:
        thrown(UnsupportedOperationException)
    }

    def "fields of their own are refused where no list of them is handed over"() {
        when:
        new FieldShape.Nested(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "FieldShape.Nested fields must not be null"
    }

    def "a file field takes one through sixty-four file extensions, and one more is over its bound"() {
        expect:
        FieldShape.File.typesFit(count) == fits

        where:
        count || fits
        0     || false
        1     || true
        64    || true
        65    || false
    }

    def "a file field's largest byte count is positive and no larger than the wire can carry exactly"() {
        expect:
        FieldShape.File.maximumBytesFit(bytes) == fits

        where:
        bytes                 || fits
        -1L                   || false
        0L                    || false
        1L                    || true
        9_007_199_254_740_991L || true
        9_007_199_254_740_992L || false
    }

    def "a file shape keeps the declaration for a code step's contextual veto, including an overbound limit"() {
        given:
        def types = (0..<65).collect { new FileExtension(".x${it}" as String) }

        when:
        def file = new FieldShape.File(types, 9_007_199_254_740_992L)

        then:
        file.types() == types
        file.maximumBytes() == 9_007_199_254_740_992L
        !FieldShape.File.typesFit(file.types().size())
        !FieldShape.File.maximumBytesFit(file.maximumBytes())
    }
}
