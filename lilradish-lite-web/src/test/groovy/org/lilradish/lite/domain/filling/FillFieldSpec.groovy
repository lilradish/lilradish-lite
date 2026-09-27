package org.lilradish.lite.domain.filling

import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldHelp
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldLabel
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.declaration.OfferedTerms
import org.lilradish.lite.domain.referencelist.ListNote
import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import org.lilradish.lite.domain.registry.EntryVersionId
import spock.lang.Specification

class FillFieldSpec extends Specification {

    static final EntryVersionId LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000106"))

    static final EntryVersionId OTHER_LIST = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000107"))

    static final OfferedTerms CATEGORIES = new OfferedTerms(
            [new OfferedTerms.Offered(new Term("Delivery"), new TermMeaning("It came late or not at all.")),
             new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge is what is disputed."))],
            new ListNote("Choose what the customer asks to have put right."))

    static final OfferedTerms SIZES =
            new OfferedTerms([new OfferedTerms.Offered(new Term("Small"), new TermMeaning("Fits a hand."))], null)

    static final FieldName NAME = new FieldName("summary")

    static final FillField HELD = new FillField(new FieldName("sku"), null, null, FieldKind.TEXT, 32, null, true, null, [])

    static final Field SKU = new Field(new FieldName("sku"), null, null, new FieldShape.Text(32), new HowMany.One(),
            new Demand.Given(true))

    def "a field carrying what its kind does not, or without what it does, is refused"() {
        when:
        new FillField(NAME, null, null, kind, longest, most, false, terms, fields)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == expectedMessage

        where:
        kind             | longest | most | terms      | fields || expectedMessage
        FieldKind.TEXT   | null    | null | null       | []     || "FillField says how long, of at least one, exactly where it is text"
        FieldKind.TEXT   | 0       | null | null       | []     || "FillField says how long, of at least one, exactly where it is text"
        FieldKind.NUMBER | 10      | null | null       | []     || "FillField says how long, of at least one, exactly where it is text"
        FieldKind.TEXT   | 10      | 0    | null       | []     || "FillField most must be at least one: 0"
        FieldKind.TERM   | null    | null | null       | []     || "FillField offers terms exactly where it is a term"
        FieldKind.DATE   | null    | null | CATEGORIES | []     || "FillField offers terms exactly where it is a term"
        FieldKind.FIELDS | null    | null | null       | []     || "FillField holds fields exactly where it is fields"
        FieldKind.YES_NO | null    | null | null       | [HELD] || "FillField holds fields exactly where it is fields"
    }

    def "each kind is put with what that kind alone says of itself, and nothing another does"() {
        when:
        def put = FillField.of([new Field(NAME, null, null, shape, howMany, new Demand.Given(false))], [(LIST): CATEGORIES])

        then:
        put == [new FillField(NAME, null, null, kind, longest, most, false, terms, fields)]

        where:
        shape                                  | howMany            || kind             | longest | most | terms      | fields
        new FieldShape.Text(100)               | new HowMany.One()  || FieldKind.TEXT   | 100     | null | null       | []
        new FieldShape.Text(1)                 | new HowMany.Many(1) || FieldKind.TEXT  | 1       | 1    | null       | []
        new FieldShape.Plain(FieldKind.NUMBER) | new HowMany.One()  || FieldKind.NUMBER | null    | null | null       | []
        new FieldShape.Plain(FieldKind.DATE)   | new HowMany.Many(5) || FieldKind.DATE  | null    | 5    | null       | []
        new FieldShape.Plain(FieldKind.MOMENT) | new HowMany.One()  || FieldKind.MOMENT | null    | null | null       | []
        new FieldShape.Plain(FieldKind.YES_NO) | new HowMany.One()  || FieldKind.YES_NO | null    | null | null       | []
        new FieldShape.Term(LIST)              | new HowMany.Many(2) || FieldKind.TERM  | null    | 2    | CATEGORIES | []
        new FieldShape.Nested([SKU])           | new HowMany.One()  || FieldKind.FIELDS | null    | null | null       | [HELD]
    }

    def "a declaration is put field by field in declared order, each term beside the terms of the list it pins"() {
        given:
        def declared = [
                new Field(new FieldName("complaint"), new FieldLabel("The complaint"), new FieldHelp("As written."),
                        new FieldShape.Text(4000), new HowMany.One(), new Demand.Given(true)),
                new Field(new FieldName("received"), null, null, new FieldShape.Plain(FieldKind.MOMENT),
                        new HowMany.Many(3), new Demand.Given(false)),
                new Field(new FieldName("category"), null, null, new FieldShape.Term(LIST), new HowMany.One(),
                        new Demand.Stands(true, FieldStanding.ABOVE_CONFIDENCE, 80)),
                new Field(new FieldName("details"), null, new FieldHelp("What it names."), new FieldShape.Nested([
                        new Field(new FieldName("size"), null, null, new FieldShape.Term(OTHER_LIST),
                                new HowMany.Many(2), new Demand.Given(true)),
                        new Field(new FieldName("urgent"), null, null, new FieldShape.Plain(FieldKind.YES_NO),
                                new HowMany.One(), new Demand.Given(false))]),
                        new HowMany.One(), new Demand.Stands(false, FieldStanding.NEVER, null))]

        when:
        def put = FillField.of(declared, [(LIST): CATEGORIES, (OTHER_LIST): SIZES])

        then:
        put == [
                new FillField(new FieldName("complaint"), new FieldLabel("The complaint"), new FieldHelp("As written."),
                        FieldKind.TEXT, 4000, null, true, null, []),
                new FillField(new FieldName("received"), null, null, FieldKind.MOMENT, null, 3, false, null, []),
                new FillField(new FieldName("category"), null, null, FieldKind.TERM, null, null, true, CATEGORIES, []),
                new FillField(new FieldName("details"), null, new FieldHelp("What it names."), FieldKind.FIELDS,
                        null, null, false, null, [
                        new FillField(new FieldName("size"), null, null, FieldKind.TERM, null, 2, true, SIZES, []),
                        new FillField(new FieldName("urgent"), null, null, FieldKind.YES_NO, null, null, false, null,
                                [])])]
    }

    def "a declaration of no fields is put as nothing to fill in, whatever lists are handed over"() {
        expect:
        FillField.of([], [(LIST): CATEGORIES]) == []
    }

    /** Only an approved version's declaration is put to anybody, and submitting refuses every one of these. */
    def "a limit or a list not chosen, a list's terms not handed over, or fields holding none is a store gone wrong"() {
        when:
        FillField.of([new Field(new FieldName("nested"), null, null, new FieldShape.Nested([
                new Field(NAME, null, null, shape, howMany, new Demand.Given(true))]),
                new HowMany.One(), new Demand.Given(true))], lists)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == expectedMessage

        where:
        shape                    | howMany               | lists                || expectedMessage
        new FieldShape.Text(null) | new HowMany.One()    | [:]                  || "FillField summary has not chosen how long"
        new FieldShape.Text(10)  | new HowMany.Many(null) | [:]                  || "FillField summary has not chosen how many"
        new FieldShape.Term(null) | new HowMany.One()    | [(LIST): CATEGORIES] || "FillField summary has not chosen which list"
        new FieldShape.Term(LIST) | new HowMany.One()    | [(OTHER_LIST): SIZES] || "FillField was handed no terms for list " + LIST.value()
        new FieldShape.Nested([]) | new HowMany.One()    | [:]                  || "FillField summary holds no fields"
    }
}
