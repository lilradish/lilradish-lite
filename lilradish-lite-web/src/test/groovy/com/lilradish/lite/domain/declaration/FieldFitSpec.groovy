package com.lilradish.lite.domain.declaration

import com.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class FieldFitSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101"))

    static final EntryVersionId OTHER_LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000102"))

    def "one line may fill several lines but several cannot fill one, including fields held many times"() {
        given:
        def source = field("value", new FieldShape.Text(30, from), new HowMany.Many(2))
        def target = field("value", new FieldShape.Text(30, to), new HowMany.Many(2))

        expect:
        FieldFit.fits(source, target) == fits
        FieldFit.fits(nested("details", [source]), nested("details", [target])) == fits

        where:
        from              | to                || fits
        TextLines.ONE     | TextLines.ONE     || true
        TextLines.ONE     | TextLines.SEVERAL || true
        TextLines.SEVERAL | TextLines.SEVERAL || true
        TextLines.SEVERAL | TextLines.ONE     || false
    }

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

    def "a case gives only what its route declares and assures every value the route requires"() {
        expect:
        differences(one, other).isEmpty() == within

        where:
        one                                                    | other                                                  || within
        []                                                     | []                                                     || true
        [text("a", 10), text("b", 20)]                         | [text("b", 20), text("a", 10)]                         || true
        [text("a", 10)]                                        | [text("a", 11)]                                        || true
        [text("a", 10)]                                        | [text("a", 10), text("b", 20)]                         || false
        [text("a", 10), text("b", 20)]                         | [text("a", 10)]                                        || false
        [text("a", 10)]                                        | [text("b", 10)]                                        || false
        [field("a", new FieldShape.Term(LIST), new HowMany.One())] | [field("a", new FieldShape.Term(OTHER_LIST), new HowMany.One())] || false
        [field("a", new FieldShape.Text(10), new HowMany.Many(2))] | [field("a", new FieldShape.Text(10), new HowMany.Many(3))]   || true
        [field("a", new FieldShape.Text(10), new HowMany.Many(2))] | [text("a", 10)]                                        || false
        [field("a", new FieldShape.Plain(FieldKind.DATE), new HowMany.One())] | [field("a", new FieldShape.Plain(FieldKind.MOMENT), new HowMany.One())] || false
        [nested("a", [text("x", 1)])]                          | [nested("a", [text("x", 1)])]                          || true
        [nested("a", [text("x", 1), text("y", 2)])]            | [nested("a", [text("y", 2), text("x", 1)])]            || true
        [nested("a", [text("x", 1)])]                          | [nested("a", [text("x", 2)])]                          || true
        [nested("a", [text("x", 1)])]                          | [text("a", 1)]                                         || false
        [demanding("a", false)]                                | [demanding("a", true)]                                 || false
        [demanding("a", true)]                                 | [demanding("a", false)]                                || true
        [nested("n", [demanding("a", false)])]                 | [nested("n", [demanding("a", true)])]                  || false
        [nested("n", [demanding("a", true)])]                  | [nested("n", [demanding("a", false)])]                 || true
    }

    def "nested differences follow case order before missing required route fields"() {
        given:
        def given = [nested("outer", [text("extra", 3), demanding("count", false),
                field("kind", new FieldShape.Plain(FieldKind.NUMBER), new HowMany.One())])]
        def declared = [nested("outer", [demanding("count", true),
                field("kind", new FieldShape.Plain(FieldKind.DATE), new HowMany.One()),
                demanding("optional", false), demanding("missing", true)])]

        expect:
        differences(given, declared) == [
                [kind: FieldFit.CaseDifferenceKind.UNDECLARED, inCase: true, path: [0, 0]],
                [kind: FieldFit.CaseDifferenceKind.MAY_GIVE_NOTHING, inCase: true, path: [0, 1]],
                [kind: FieldFit.CaseDifferenceKind.OTHER_KIND, inCase: true, path: [0, 2]],
                [kind: FieldFit.CaseDifferenceKind.MAY_GIVE_NOTHING, inCase: false, path: [0, 3]]]
    }

    def "one field with several differences reports its first reason by kind then limit then assurance"() {
        expect:
        differences([source], [target])*.kind == [reason]

        where:
        source | target || reason
        new Field(new FieldName("x"), null, null, new FieldShape.Plain(FieldKind.NUMBER),
                new HowMany.Many(5), new Demand.Given(false)) |
                new Field(new FieldName("x"), null, null, new FieldShape.Text(10),
                        new HowMany.Many(3), new Demand.Given(true)) || FieldFit.CaseDifferenceKind.OTHER_KIND
        new Field(new FieldName("x"), null, null, new FieldShape.Text(20),
                new HowMany.Many(5), new Demand.Given(false)) |
                new Field(new FieldName("x"), null, null, new FieldShape.Text(10),
                        new HowMany.Many(3), new Demand.Given(true)) || FieldFit.CaseDifferenceKind.PAST_LIMIT
        demanding("x", false) | demanding("x", true) || FieldFit.CaseDifferenceKind.MAY_GIVE_NOTHING
    }

    def "what is fitted and compared is never absent"() {
        when:
        asked == "visitCaseDifferences" ? FieldFit.visitCaseDifferences(one, other, { kind, inCase, path, depth -> }) :
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
        "visitCaseDifferences" | null | []         || "FieldFit given must not be null"
        "visitCaseDifferences" | []   | null           || "FieldFit declared must not be null"
    }

    private static List<Map<String, Object>> differences(List<Field> given, List<Field> declared) {
        def found = []
        FieldFit.visitCaseDifferences(given, declared, { kind, inCase, path, depth ->
            found.add([kind: kind, inCase: inCase, path: Arrays.copyOf(path, depth).toList()])
        })
        found
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
