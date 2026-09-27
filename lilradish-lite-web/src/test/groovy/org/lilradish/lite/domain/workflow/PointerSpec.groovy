package org.lilradish.lite.domain.workflow

import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import spock.lang.Specification

class PointerSpec extends Specification {

    static final Field ORDER = field("order", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One(), true)

    static final Field SKU = field("sku", new FieldShape.Text(32), new HowMany.One(), true)

    static final Field LINES = field("lines", new FieldShape.Nested([SKU]), new HowMany.Many(5), true)

    /** Need not be given, so nothing it holds is always there, whatever each of those says of itself. */
    static final Field DETAILS = field("details", new FieldShape.Nested([ORDER, LINES]), new HowMany.One(), false)

    static final Field SHADOW = field("details", new FieldShape.Text(10), new HowMany.One(), true)

    static final Field LABELS = field("labels", new FieldShape.Text(20), new HowMany.Many(3), true)

    static final List<Field> LEVEL = [DETAILS, SHADOW, ORDER, LABELS]

    /** As long as the store holds a path, and not a character longer. */
    def "a pointer running to as many characters as a stored path holds is read"() {
        expect:
        new Pointer([new FieldName("a" * 63)] * 16).published().length() == 1023
    }

    def "a pointer running one character past what a stored path holds is refused"() {
        when:
        new Pointer([new FieldName("a" * 63)] * 16 + [new FieldName("b")])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Pointer runs to 1025 characters, past 1023"
    }

    def "a pointer names at least one field, and nothing absent"() {
        when:
        new Pointer(names)

        then:
        def refused = thrown(expectedException)
        refused.message == expectedMessage

        where:
        names || expectedException        | expectedMessage
        []    || IllegalArgumentException | "Pointer must name at least one field"
        null  || NullPointerException     | "Pointer names must not be null"
    }

    def "names joined by dots are read as the fields they name, and spelt back as they were"() {
        when:
        def pointer = Pointer.parse(spelled)

        then:
        pointer.names()*.value() == names
        pointer.published() == spelled

        where:
        spelled          || names
        "order"          || ["order"]
        "details.order"  || ["details", "order"]
        "a.b_2.c"        || ["a", "b_2", "c"]
    }

    /** A name is a field's name, so no joiner, capital or empty place ever reads as one. */
    def "what names no field in some place is refused, each name as a field's name is"() {
        when:
        Pointer.parse(spelled)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == expectedMessage

        where:
        spelled   || expectedMessage
        ""        || "FieldName must not be empty"
        "a..b"    || "FieldName must not be empty"
        "a."      || "FieldName must not be empty"
        ".a"      || "FieldName must not be empty"
        "Order"   || "FieldName must start with a lowercase letter"
        "a-b"     || "FieldName must contain only lowercase letters, digits and _"
    }

    def "a pointer is spelt from something"() {
        when:
        Pointer.parse(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Pointer spelled must not be null"
    }

    def "one pointer is within another where it names that field or one the field holds"() {
        expect:
        Pointer.parse(inner).within(Pointer.parse(outer)) == within

        where:
        inner               | outer             || within
        "details"           | "details"         || true
        "details.order"     | "details"         || true
        "details.lines.sku" | "details"         || true
        "details"           | "details.order"   || false
        "detailsx"          | "details"         || false
        "order"             | "details"         || false
    }

    /** A value is always there only where the field and every field holding it must be given. */
    def "a pointer reaches the field it names through fields each holding one, saying whether it is always there"() {
        expect:
        Pointer.parse(spelled).reach(LEVEL) == new Pointer.Reach.At(reached, given)

        where:
        spelled                || reached | given
        "order"                || ORDER   | true
        "labels"               || LABELS  | true
        "details.order"        || ORDER   | false
        "details.lines"        || LINES   | false
    }

    /** Two fields alike by name are a draft's problem already; the one first in declared order is what is read. */
    def "a name held by two fields beside each other reaches the first of them"() {
        expect:
        Pointer.parse("details").reach(LEVEL) == new Pointer.Reach.At(DETAILS, false)
        Pointer.parse("details").reach([SHADOW, DETAILS]) == new Pointer.Reach.At(SHADOW, true)
    }

    /** A place among many is only known once values are held, so a pointer never names one. */
    def "a pointer through a field holding many reaches no place, and one naming nothing reaches nothing"() {
        expect:
        Pointer.parse(spelled).reach(LEVEL).class == reached

        where:
        spelled                 || reached
        "details.lines.sku"     || Pointer.Reach.IntoMany
        "labels.first"          || Pointer.Reach.Nowhere
        "missing"               || Pointer.Reach.Nowhere
        "details.missing"       || Pointer.Reach.Nowhere
        "order.more"            || Pointer.Reach.Nowhere
        "details.order.more"    || Pointer.Reach.Nowhere
    }

    def "what a pointer is asked of is never absent"() {
        when:
        Pointer.parse("order")."${asked}"(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        asked    || expectedMessage
        "within" || "Pointer other must not be null"
        "reach"  || "Pointer level must not be null"
    }

    def "where a pointer leads to a field, the field is named"() {
        when:
        new Pointer.Reach.At(null, true)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Pointer.Reach.At field must not be null"
    }

    private static Field field(String name, FieldShape shape, HowMany howMany, boolean mustBe) {
        new Field(new FieldName(name), null, null, shape, howMany, new Demand.Given(mustBe))
    }
}
