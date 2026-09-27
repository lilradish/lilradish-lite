package org.lilradish.lite.domain.declaration

import static org.lilradish.lite.domain.registry.ContentProblemCode.FLOOR_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.LIMIT_PAST_LARGEST
import static org.lilradish.lite.domain.registry.ContentProblemCode.LIST_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.LONGEST_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.MOST_MISSING
import static org.lilradish.lite.domain.registry.ContentProblemCode.NAME_REPEATED
import static org.lilradish.lite.domain.registry.ContentProblemCode.NO_FIELDS_HELD
import static org.lilradish.lite.domain.registry.ContentProblemCode.STANDING_MISSING

import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.testutil.OfferedLists
import spock.lang.Specification

class DeclarationSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000101"))

    static final EntryVersionId OTHER_LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000102"))

    static final Demand STANDS = new Demand.Stands(true, FieldStanding.NEVER, null)

    static final Demand GIVEN = new Demand.Given(true)

    static final Demand OPTIONAL = new Demand.Given(false)

    static final HowMany MOST = new HowMany.Many(Integer.MAX_VALUE)

    static final Demands TAKEN = Demands.ofQuestion(DeclarationSide.TAKES)

    static final Demands GIVEN_BACK = Demands.ofQuestion(DeclarationSide.GIVES)

    static Field field(String name, FieldShape shape = new FieldShape.Text(100), Demand demand = STANDS,
                       HowMany howMany = new HowMany.One()) {
        new Field(new FieldName(name), null, null, shape, howMany, demand)
    }

    static Field text(String name, int longest, HowMany howMany = new HowMany.One()) {
        field(name, new FieldShape.Text(longest), GIVEN, howMany)
    }

    static Field holding(String name, Demand demand, List<Field> fields) {
        field(name, new FieldShape.Nested(fields), demand)
    }

    static Declaration taken(List<Field> fields) {
        new Declaration(DeclarationSide.TAKES, TAKEN, fields)
    }

    static Declaration givenBack(List<Field> fields) {
        new Declaration(DeclarationSide.GIVES, GIVEN_BACK, fields)
    }

    /** {@code levels} fields each holding the next, the innermost of text, every one named {@code name}. */
    static Field nested(int levels, String name) {
        def innermost = field(name, new FieldShape.Text(10), GIVEN)
        (1..<levels).inject(innermost) { held, level -> holding(name, GIVEN, [held]) }
    }

    /** Every level of what is taken says whether it must be given; only a value given back says how it stands. */
    def "each half holds the demand its host asks of it, at every depth it is asked"() {
        when:
        def declared = new Declaration(side, Demands.ofQuestion(side), fields)

        then:
        declared.side() == side
        declared.fields() == fields

        where:
        side                  | fields
        DeclarationSide.TAKES | [holding("details", GIVEN, [field("product", new FieldShape.Text(10), GIVEN)])]
        DeclarationSide.GIVES | [holding("details", STANDS, [field("product", new FieldShape.Text(10), OPTIONAL)])]
        DeclarationSide.TAKES | []
        DeclarationSide.GIVES | []
    }

    /** A host that lets nothing stand, as what a workflow gives back, is one the rule is written for too. */
    def "a half given back whose host asks no standing holds fields saying only whether they must be given"() {
        when:
        def declared = new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES),
                [holding("details", GIVEN, [field("product", new FieldShape.Text(10), OPTIONAL)])])

        then:
        declared.fields()*.demand() == [GIVEN]
        (declared.fields()[0].shape() as FieldShape.Nested).fields()*.demand() == [OPTIONAL]
    }

    def "a demand its host does not ask there is refused, naming which it was and where"() {
        when:
        new Declaration(side, Demands.ofQuestion(side), [first])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Declaration asking " + Demands.ofQuestion(side) + " holds a field demanding " + demanding

        where:
        side                  | first                                                         || demanding
        DeclarationSide.TAKES | field("summary", new FieldShape.Text(10), STANDS)             || "STANDS on its first level"
        DeclarationSide.GIVES | field("summary", new FieldShape.Text(10), GIVEN)              || "GIVEN on its first level"
        DeclarationSide.GIVES | holding("details", STANDS, [field("product", new FieldShape.Text(10), STANDS)]) || "STANDS below it"
        DeclarationSide.TAKES | holding("details", GIVEN, [field("product", new FieldShape.Text(10), STANDS)])  || "STANDS below it"
    }

    /** What is taken never stands: only what a question gives back does, and only where no other field holds it. */
    def "a half of what is taken is refused a host asking it to stand"() {
        when:
        new Declaration(DeclarationSide.TAKES, demands, [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Declaration of what is taken cannot ask " + demands

        where:
        demands << [new Demands(Demands.Kind.STANDS, Demands.Kind.GIVEN)]
    }

    def "a half given back may stand at its first level, or say only whether it must be given"() {
        expect:
        new Declaration(DeclarationSide.GIVES, demands, []).demands() == demands

        where:
        demands << [new Demands(Demands.Kind.STANDS, Demands.Kind.GIVEN), new Demands(Demands.Kind.GIVEN, Demands.Kind.GIVEN)]
    }

    def "a half counts the fields it holds, at every depth together"() {
        expect:
        taken(fields).fieldsHeld() == held

        where:
        fields                                                                                      || held
        []                                                                                          || 0
        [field("summary", new FieldShape.Text(10), GIVEN)]                                          || 1
        [holding("details", GIVEN, [field("product", new FieldShape.Text(10), GIVEN),
                                    holding("sku", GIVEN, [field("code", new FieldShape.Text(5), GIVEN)])])] || 4
    }

    /** Sixteen names of 63 joined by fifteen dots is 1023, the most anything may be bound through. */
    def "a field whose path runs to 1023 is held, and one running past it refused"() {
        given:
        def deepest = nested(16, "a".repeat(63))

        expect:
        taken([deepest]).fields() == [deepest]

        when:
        taken([holding("b", GIVEN, [deepest])])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Declaration holds a field whose path runs to 1025 characters, past 1023"
    }

    def "a field held 32 deep is held, and one 33 deep refused, however short its names"() {
        expect:
        taken([nested(32, "a")]).fields().size() == 1

        when:
        taken([nested(33, "a")])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Declaration holds a field 33 deep, past 32"
    }

    def "a half holds 256 fields at every depth together, and 257 is refused"() {
        given:
        def many = { int count -> (1..count).collect { field("f" + it, new FieldShape.Text(1), GIVEN) } }

        expect:
        taken([holding("details", GIVEN, many(255))]).fields().size() == 1

        when:
        taken([holding("details", GIVEN, many(256))])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Declaration holds 257 fields, more than the 256 a half holds"
    }

    def "a half is held apart from the list it was handed in"() {
        given:
        def handed = [field("summary")]

        when:
        def declared = givenBack(handed)
        handed.add(field("other"))

        then:
        declared.fields() == [field("summary")]
    }

    def "a half says which it is, what it asks and what it holds, or is refused"() {
        when:
        new Declaration(side, demands, fields)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Declaration " + missing + " must not be null"

        where:
        side                  | demands    | fields || missing
        null                  | GIVEN_BACK | []     || "side"
        DeclarationSide.GIVES | null       | []     || "demands"
        DeclarationSide.GIVES | GIVEN_BACK | null   || "fields"
    }

    def "a path of names below another runs one dot and its own name further, and a first-level one its name alone"() {
        expect:
        Declaration.pathBelow(above, new FieldName(name)) == path

        where:
        above | name      || path
        0     | "details" || 7
        7     | "product" || 15
        1016  | "a"       || 1018
    }

    def "a whole half has no problem to name, and holds"() {
        given:
        def whole = givenBack([
                field("category", new FieldShape.Term(LIST), new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, 80)),
                field("tags", new FieldShape.Text(20), new Demand.Stands(false, FieldStanding.ALWAYS, null),
                        new HowMany.Many(5)),
                holding("details", STANDS, [field("product", new FieldShape.Plain(FieldKind.NUMBER), OPTIONAL)])])

        expect:
        whole.problems(OfferedLists.offering([(LIST): ["Billing", "Refund"]])) == []
        whole.holds()
    }

    /** Each where it sits, counted from zero at each level, in the order the half reads and its codes are declared. */
    def "every place a half does not hold is named where it sits, in the order it reads"() {
        given:
        def declared = givenBack([
                field("summary", new FieldShape.Text(null), new Demand.Stands(true, null, null), new HowMany.Many(null)),
                field("category", new FieldShape.Term(null), new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, null)),
                holding("details", STANDS, [
                        field("product", new FieldShape.Text(10), GIVEN),
                        field("product", new FieldShape.Text(null), OPTIONAL),
                        holding("empty", GIVEN, [])]),
                field("summary", new FieldShape.Plain(FieldKind.DATE), STANDS)])

        expect:
        declared.problems([:]) == [
                new FieldProblem(LONGEST_MISSING, [0]),
                new FieldProblem(MOST_MISSING, [0]),
                new FieldProblem(STANDING_MISSING, [0]),
                new FieldProblem(LIST_MISSING, [1]),
                new FieldProblem(FLOOR_MISSING, [1]),
                new FieldProblem(NAME_REPEATED, [2, 1]),
                new FieldProblem(LONGEST_MISSING, [2, 1]),
                new FieldProblem(NO_FIELDS_HELD, [2, 2]),
                new FieldProblem(NAME_REPEATED, [3])]
        !declared.holds()
    }

    /**
     * A text at its limit is written between two quotes, and many between brackets with a comma between each; a
     * limit not chosen yet counts many as one. Either side of the line; holding only asks what needs no list.
     */
    def "a field one value of which could be written out longer than one value may be is named, and one at it is not"() {
        given:
        def declared = taken([field("body", new FieldShape.Text(longest), GIVEN, howMany)])

        expect:
        declared.problems([:])*.code() == named
        declared.holds() == (named - LIMIT_PAST_LARGEST).isEmpty()

        where:
        longest           | howMany                             || named
        8_388_606         | new HowMany.One()                   || []
        8_388_607         | new HowMany.One()                   || [LIMIT_PAST_LARGEST]
        4_194_300         | new HowMany.Many(2)                 || []
        4_194_301         | new HowMany.Many(2)                 || [LIMIT_PAST_LARGEST]
        Integer.MAX_VALUE | new HowMany.Many(Integer.MAX_VALUE) || [LIMIT_PAST_LARGEST]
        8_388_604         | new HowMany.Many(null)              || [MOST_MISSING]
        8_388_605         | new HowMany.Many(null)              || [MOST_MISSING, LIMIT_PAST_LARGEST]
    }

    /**
     * Each field held is written beneath its quoted name, a term at its list's longest and a plain kind at its
     * longest written; past what a long counts, it is named all the same, and so is each field within it past it.
     */
    def "a field holding fields is named where one value of it, names and marks included, could be written out too long"() {
        given:
        def declared = taken([field("details", new FieldShape.Nested(held), GIVEN)])

        expect:
        declared.problems(OfferedLists.offering(terms)) == named.collect { new FieldProblem(LIMIT_PAST_LARGEST, it) }

        where:
        held                                                                   | terms                            || named
        [text("body", 8_388_597)]                                              | [:]                              || []
        [text("body", 8_388_598)]                                              | [:]                              || [[0]]
        [text("body", 4_194_293), text("note", 4_194_294)]                     | [:]                              || []
        [text("body", 4_194_293), text("note", 4_194_295)]                     | [:]                              || [[0]]
        [field("kind", new FieldShape.Term(LIST), GIVEN, new HowMany.Many(64_035))] | [(LIST): ["x" * 128, "Billing"]] || []
        [field("kind", new FieldShape.Term(LIST), GIVEN, new HowMany.Many(64_036))] | [(LIST): ["x" * 128, "Billing"]] || [[0], [0, 0]]
        [field("kind", new FieldShape.Term(LIST), GIVEN, new HowMany.Many(64_036))] | [(LIST): ["Billing"]]            || []
        [field("kind", new FieldShape.Term(LIST), GIVEN, MOST)]                | [:]                              || []
        [field("amount", new FieldShape.Plain(FieldKind.NUMBER), GIVEN, new HowMany.Many(204_599))] | [:] || []
        [field("amount", new FieldShape.Plain(FieldKind.NUMBER), GIVEN, new HowMany.Many(204_600))] | [:] || [[0]]
        [text("body", Integer.MAX_VALUE, MOST)]                                | [:]                              || [[0], [0, 0]]
        [field("inner", new FieldShape.Nested([text("body", Integer.MAX_VALUE, MOST)]), GIVEN, MOST)] | [:] || [[0], [0, 0], [0, 0, 0]]
    }

    /** holds() stops at the first problem it meets, so one only a nested field has must still be reached. */
    def "a half whose only problem is in a field held inside another does not hold"() {
        given:
        def declared = taken([
                field("summary", new FieldShape.Text(10), GIVEN),
                holding("details", GIVEN, [
                        field("sku", new FieldShape.Text(10), GIVEN),
                        holding("inner", GIVEN, [field("product", shape, GIVEN)])])])

        expect:
        declared.problems([:]) == [new FieldProblem(code, [1, 1, 0])]
        !declared.holds()

        where:
        shape                      || code
        new FieldShape.Text(null)  || LONGEST_MISSING
        new FieldShape.Term(null)  || LIST_MISSING
        new FieldShape.Nested([])  || NO_FIELDS_HELD
    }

    /** A name is repeated only beside another: the same name at another level, or under another field, is its own. */
    def "a name shared only across levels or across fields is no repetition"() {
        expect:
        taken([
                holding("details", GIVEN, [field("details", new FieldShape.Text(10), GIVEN)]),
                holding("other", GIVEN, [field("details", new FieldShape.Text(10), GIVEN)])
        ]).problems([:]) == []
    }

    def "what a half names is a list of its own, which nobody can add to"() {
        when:
        taken([field("summary", new FieldShape.Text(null), GIVEN)]).problems([:]).add(new FieldProblem(NAME_REPEATED, [0]))

        then:
        thrown(UnsupportedOperationException)
    }

    /** Each field beneath its quoted name, a comma between each, as problems counts one value of a field. */
    def "all a half holds is written out as one value, each field at its limits, however many it holds"() {
        expect:
        taken(fields).longestWritten(OfferedLists.offering([(LIST): terms])) == length

        where:
        fields                                                                        | terms                 || length
        []                                                                            | ["Billing", "Refund"] || 2
        [text("body", 10)]                                                            | ["Billing", "Refund"] || 21
        [text("body", 10), field("due", new FieldShape.Plain(FieldKind.DATE), GIVEN)] | ["Billing", "Refund"] || 40
        [field("kind", new FieldShape.Term(LIST), OPTIONAL)]                          | ["Billing", "Refund"] || 18
        [field("kind", new FieldShape.Term(LIST), OPTIONAL)]                          | []                    || 13
        [field("kind", new FieldShape.Term(OTHER_LIST), OPTIONAL)]                    | ["Billing", "Refund"] || 13
        [text("body", Integer.MAX_VALUE, MOST)]                                       | ["Billing", "Refund"] || Declaration.MOST_SENT + 1
    }
}
