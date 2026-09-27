package org.lilradish.lite.domain.declaration

import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class FieldFitSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101"))

    static final EntryVersionId OTHER_LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000102"))

    /** Nothing longer or more numerous may reach a field than it declares, and one never turns into many. */
    def "what a field holds fits another where it is the same kind, one or many alike, and within its limits"() {
        expect:
        FieldFit.fits(field("value", held, heldMany), field("value", taken, takenMany)) == fits

        where:
        held                                   | heldMany              | taken                                  | takenMany             || fits
        new FieldShape.Text(100)               | new HowMany.One()     | new FieldShape.Text(100)               | new HowMany.One()     || true
        new FieldShape.Text(99)                | new HowMany.One()     | new FieldShape.Text(100)               | new HowMany.One()     || true
        new FieldShape.Text(101)               | new HowMany.One()     | new FieldShape.Text(100)               | new HowMany.One()     || false
        new FieldShape.Text(null)              | new HowMany.One()     | new FieldShape.Text(100)               | new HowMany.One()     || true
        new FieldShape.Text(101)               | new HowMany.One()     | new FieldShape.Text(null)              | new HowMany.One()     || true
        new FieldShape.Text(10)                | new HowMany.Many(3)   | new FieldShape.Text(10)                | new HowMany.Many(3)   || true
        new FieldShape.Text(10)                | new HowMany.Many(4)   | new FieldShape.Text(10)                | new HowMany.Many(3)   || false
        new FieldShape.Text(10)                | new HowMany.Many(null)| new FieldShape.Text(10)                | new HowMany.Many(3)   || true
        new FieldShape.Text(10)                | new HowMany.One()     | new FieldShape.Text(10)                | new HowMany.Many(3)   || false
        new FieldShape.Text(10)                | new HowMany.Many(1)   | new FieldShape.Text(10)                | new HowMany.One()     || false
        new FieldShape.Text(10)                | new HowMany.One()     | new FieldShape.Plain(FieldKind.NUMBER) | new HowMany.One()     || false
        new FieldShape.Plain(FieldKind.DATE)   | new HowMany.One()     | new FieldShape.Plain(FieldKind.MOMENT) | new HowMany.One()     || false
        new FieldShape.Plain(FieldKind.DATE)   | new HowMany.One()     | new FieldShape.Plain(FieldKind.DATE)   | new HowMany.One()     || true
        new FieldShape.Term(LIST)              | new HowMany.One()     | new FieldShape.Term(LIST)              | new HowMany.One()     || true
        new FieldShape.Term(OTHER_LIST)        | new HowMany.One()     | new FieldShape.Term(LIST)              | new HowMany.One()     || false
        new FieldShape.Term(null)              | new HowMany.One()     | new FieldShape.Term(LIST)              | new HowMany.One()     || true
        new FieldShape.Term(LIST)              | new HowMany.One()     | new FieldShape.Term(null)              | new HowMany.One()     || true
    }

    /** By name and not by place: a field holding fields holds exactly the same names, each fitting its own. */
    def "a field holding fields fits another holding the same names, each fitting, in whatever order"() {
        given:
        def taken = field("details", new FieldShape.Nested([
                field("sku", new FieldShape.Text(10), new HowMany.One()),
                field("count", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One())]), new HowMany.One())

        expect:
        FieldFit.fits(field("details", new FieldShape.Nested(held), new HowMany.One()), taken) == fits

        where:
        held                                                                                              || fits
        [field("sku", new FieldShape.Text(10), new HowMany.One()),
         field("count", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One())]                       || true
        [field("count", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One()),
         field("sku", new FieldShape.Text(5), new HowMany.One())]                                         || true
        [field("sku", new FieldShape.Text(11), new HowMany.One()),
         field("count", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One())]                       || false
        [field("sku", new FieldShape.Text(10), new HowMany.One())]                                        || false
        [field("sku", new FieldShape.Text(10), new HowMany.One()),
         field("amount", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One())]                      || false
        [field("sku", new FieldShape.Text(10), new HowMany.One()),
         field("count", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One()),
         field("extra", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One())]                       || false
    }

    /** What people read a field as is not what a value is; what a field asks is its host's, not its value's. */
    def "fitting reads neither the label, the help, the name nor what a field asks"() {
        given:
        def read = new Field(new FieldName("summary"), new FieldLabel("Summary"), new FieldHelp("As written."),
                new FieldShape.Text(10), new HowMany.One(), new Demand.Stands(false, FieldStanding.NEVER, null))
        def taken = new Field(new FieldName("text"), null, null, new FieldShape.Text(10), new HowMany.One(),
                new Demand.Given(true))

        expect:
        FieldFit.fits(read, taken)
    }

    /** Only an inner field the target must be given asks anything of the source's field of the same name. */
    def "a field holding fields is given within where each inner field the target must be given the source must give"() {
        expect:
        FieldFit.givenWithin(source, target) == given

        where:
        source                                                        | target                                                        || given
        nested("a", [demanding("x", false)])                          | nested("a", [demanding("x", true)])                           || false
        nested("a", [demanding("x", true)])                           | nested("a", [demanding("x", true)])                           || true
        nested("a", [demanding("x", true)])                           | nested("a", [demanding("x", false)])                          || true
        nested("a", [demanding("x", false)])                          | nested("a", [demanding("x", false)])                          || true
        nested("a", [nested("b", [demanding("x", false)])])           | nested("a", [nested("b", [demanding("x", true)])])            || false
        field("a", new FieldShape.Nested([demanding("x", false)]), new HowMany.Many(3)) |
                field("a", new FieldShape.Nested([demanding("x", true)]), new HowMany.Many(3))                               || false
        nested("a", [demanding("y", false)])                          | nested("a", [demanding("x", true)])                           || true
        demanding("a", false)                                         | demanding("a", true)                                          || true
    }

    def "two levels are alike where they name the same fields, each declaring the same, in whatever order"() {
        expect:
        FieldFit.alike(one, other) == alike

        where:
        one                                                    | other                                                  || alike
        []                                                     | []                                                     || true
        [text("a", 10), text("b", 20)]                         | [text("b", 20), text("a", 10)]                         || true
        [text("a", 10)]                                        | [text("a", 11)]                                        || false
        [text("a", 10)]                                        | [text("a", 10), text("b", 20)]                         || false
        [text("a", 10), text("b", 20)]                         | [text("a", 10)]                                        || false
        [text("a", 10)]                                        | [text("b", 10)]                                        || false
        [field("a", new FieldShape.Term(LIST), new HowMany.One())] | [field("a", new FieldShape.Term(OTHER_LIST), new HowMany.One())] || false
        [field("a", new FieldShape.Text(10), new HowMany.Many(2))] | [field("a", new FieldShape.Text(10), new HowMany.Many(3))]   || false
        [field("a", new FieldShape.Text(10), new HowMany.Many(2))] | [text("a", 10)]                                        || false
        [field("a", new FieldShape.Plain(FieldKind.DATE), new HowMany.One())] | [field("a", new FieldShape.Plain(FieldKind.MOMENT), new HowMany.One())] || false
        [nested("a", [text("x", 1)])]                          | [nested("a", [text("x", 1)])]                          || true
        [nested("a", [text("x", 1), text("y", 2)])]            | [nested("a", [text("y", 2), text("x", 1)])]            || true
        [nested("a", [text("x", 1)])]                          | [nested("a", [text("x", 2)])]                          || false
        [nested("a", [text("x", 1)])]                          | [text("a", 1)]                                         || false
        [demanding("a", false)]                                | [demanding("a", true)]                                 || false
        [demanding("a", true)]                                 | [demanding("a", false)]                                || true
        [nested("n", [demanding("a", false)])]                 | [nested("n", [demanding("a", true)])]                  || false
        [nested("n", [demanding("a", true)])]                  | [nested("n", [demanding("a", false)])]                 || true
    }

    def "what is fitted and compared is never absent"() {
        when:
        FieldFit."${asked}"(one, other)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        asked   | one            | other          || expectedMessage
        "fits"  | null           | text("a", 1)   || "FieldFit source must not be null"
        "fits"  | text("a", 1)   | null           || "FieldFit target must not be null"
        "givenWithin" | null     | text("a", 1)   || "FieldFit source must not be null"
        "givenWithin" | text("a", 1) | null       || "FieldFit target must not be null"
        "alike" | null           | []             || "FieldFit one must not be null"
        "alike" | []             | null           || "FieldFit other must not be null"
    }

    private static Field text(String name, int longest) {
        field(name, new FieldShape.Text(longest), new HowMany.One())
    }

    private static Field demanding(String name, boolean mustBe) {
        new Field(new FieldName(name), null, null, new FieldShape.Text(10), new HowMany.One(), new Demand.Given(mustBe))
    }

    private static Field nested(String name, List<Field> held) {
        field(name, new FieldShape.Nested(held), new HowMany.One())
    }

    private static Field field(String name, FieldShape shape, HowMany howMany) {
        new Field(new FieldName(name), null, null, shape, howMany, new Demand.Given(true))
    }
}
