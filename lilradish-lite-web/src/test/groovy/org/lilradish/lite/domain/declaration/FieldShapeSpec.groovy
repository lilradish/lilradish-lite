package org.lilradish.lite.domain.declaration

import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class FieldShapeSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101"))

    static final Field HELD = new Field(new FieldName("product"), null, null, new FieldShape.Text(128),
            new HowMany.One(), new Demand.Given(true))

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
}
