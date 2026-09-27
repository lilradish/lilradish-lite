package org.lilradish.lite.domain.declaration

import spock.lang.Specification

class FieldSpec extends Specification {

    static final FieldName NAME = new FieldName("complaint")

    def "a field carries its label and help where it has them, and nothing where it does not"() {
        when:
        def field = new Field(NAME, label, help, new FieldShape.Text(4000), new HowMany.One(), new Demand.Given(true))

        then:
        field.label() == label
        field.help() == help

        where:
        label                            | help
        new FieldLabel("The complaint")  | new FieldHelp("As written.")
        null                             | null
    }

    def "a field is refused without each thing every field says"() {
        when:
        new Field(name, null, null, shape, howMany, demand)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Field " + missing + " must not be null"

        where:
        name | shape                     | howMany           | demand                  || missing
        null | new FieldShape.Text(1)    | new HowMany.One() | new Demand.Given(true)  || "name"
        NAME | null                      | new HowMany.One() | new Demand.Given(true)  || "shape"
        NAME | new FieldShape.Text(1)    | null              | new Demand.Given(true)  || "howMany"
        NAME | new FieldShape.Text(1)    | new HowMany.One() | null                    || "demand"
    }
}
