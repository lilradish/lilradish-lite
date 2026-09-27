package org.lilradish.lite.domain.declaration

import org.lilradish.lite.domain.referencelist.Term
import org.lilradish.lite.domain.referencelist.TermMeaning
import spock.lang.Specification

class AskedFieldSpec extends Specification {

    static final FieldName NAME = new FieldName("summary")

    static final OfferedTerms OFFERED =
            new OfferedTerms([new OfferedTerms.Offered(new Term("Billing"), new TermMeaning("A charge."))], null)

    static final AskedField HELD =
            new AskedField(new FieldName("sku"), FieldKind.TEXT, 32, null, null, [], false, false)

    /**
     * Held to its components by their names: a model is told a field's name, kind, limits, terms and fields,
     * whether it must be given and whether a confidence is asked, and a label, a help or a floor added here would
     * be told it too.
     */
    def "what a model is told of a field is its name, kind, limits, terms, fields, whether it must be given and whether a confidence is asked"() {
        expect:
        AskedField.recordComponents*.name ==
                ["name", "kind", "longest", "most", "terms", "fields", "mustBeGiven", "confidenceAsked"]
    }

    def "each kind carries what that kind alone says of itself, and nothing another does"() {
        when:
        def field = new AskedField(NAME, kind, longest, most, terms, fields, false, false)

        then:
        field.kind() == kind
        field.longest() == longest
        field.most() == most
        field.terms() == terms
        field.fields() == fields

        where:
        kind             | longest | most | terms   | fields
        FieldKind.TEXT   | 100     | null | null    | []
        FieldKind.TEXT   | 1       | 3    | null    | []
        FieldKind.MOMENT | null    | null | null    | []
        FieldKind.TERM   | null    | 1    | OFFERED | []
        FieldKind.FIELDS | null    | null | null    | [HELD]
        FieldKind.FIELDS | null    | null | null    | []
    }

    def "a field told what its kind does not carry, or without what it does, is refused"() {
        when:
        new AskedField(NAME, kind, longest, most, terms, fields, false, false)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == expectedMessage

        where:
        kind             | longest | most | terms   | fields || expectedMessage
        FieldKind.TEXT   | null    | null | null    | []     || "AskedField says how long, of at least one, exactly where it is text"
        FieldKind.TEXT   | 0       | null | null    | []     || "AskedField says how long, of at least one, exactly where it is text"
        FieldKind.NUMBER | 10      | null | null    | []     || "AskedField says how long, of at least one, exactly where it is text"
        FieldKind.TEXT   | 10      | 0    | null    | []     || "AskedField most must be at least one: 0"
        FieldKind.TERM   | null    | null | null    | []     || "AskedField offers terms exactly where it is a term"
        FieldKind.DATE   | null    | null | OFFERED | []     || "AskedField offers terms exactly where it is a term"
        FieldKind.YES_NO | null    | null | null    | [HELD] || "AskedField holds fields only where it is fields"
    }

    def "a field as a model is told it is refused without its name, its kind or its fields"() {
        when:
        new AskedField(name, kind, null, null, null, fields, false, false)

        then:
        def refused = thrown(NullPointerException)
        refused.message == expectedMessage

        where:
        name | kind             | fields || expectedMessage
        null | FieldKind.NUMBER | []     || "AskedField name must not be null"
        NAME | null             | []     || "AskedField kind must not be null"
        NAME | FieldKind.NUMBER | null   || "AskedField fields must not be null"
    }

    def "fields a model is told are held apart from the list they were handed in"() {
        given:
        def handed = [HELD]

        when:
        def field = new AskedField(NAME, FieldKind.FIELDS, null, null, null, handed, false, false)
        handed.clear()

        then:
        field.fields() == [HELD]
    }
}
