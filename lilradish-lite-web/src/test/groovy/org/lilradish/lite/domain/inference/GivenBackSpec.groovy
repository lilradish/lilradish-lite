package org.lilradish.lite.domain.inference

import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

class GivenBackSpec extends Specification {

    def "what fits keeps its values as they were when it was made, and cannot be changed after"() {
        given:
        def values = [(new FieldName("summary")): new JsonValue.JsonString("hello")]

        when:
        def fits = new GivenBack.Fits(values)
        values.put(new FieldName("amount"), new JsonValue.JsonNull())
        fits.values().put(new FieldName("tags"), new JsonValue.JsonNull())

        then:
        thrown(UnsupportedOperationException)
        fits.values() == [(new FieldName("summary")): new JsonValue.JsonString("hello")]
    }

    def "what fits is refused by name when it holds no values at all"() {
        when:
        new GivenBack.Fits(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "Fits values must not be null"
    }

    def "a misfit keeps its path as it was when it was made, and cannot be changed after"() {
        given:
        def path = [new FieldName("details")]

        when:
        def misfit = new GivenBack.Misfit(DidNotFitReason.TOO_LONG, path, null)
        path.add(new FieldName("codes"))
        misfit.path().add(new FieldName("product"))

        then:
        thrown(UnsupportedOperationException)
        misfit.path() == [new FieldName("details")]
    }

    def "a misfit is refused by name without a reason or a path"() {
        when:
        new GivenBack.Misfit(reason, path, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == message

        where:
        reason                   | path                          || message
        null                     | [new FieldName("details")]    || "Misfit reason must not be null"
        DidNotFitReason.TOO_LONG | null                          || "Misfit path must not be null"
    }

    def "a misfit names an undeclared member exactly where one is unknown, and is refused otherwise"() {
        when:
        new GivenBack.Misfit(reason, [new FieldName("details")], undeclared)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Misfit names an undeclared member exactly where one is unknown"

        where:
        reason                           | undeclared
        DidNotFitReason.FIELD_UNKNOWN    | null
        DidNotFitReason.NOTHING_GIVEN    | "size"
        DidNotFitReason.TOO_LONG_TO_KEEP | ""
    }

    def "a misfit keeps the undeclared member as it came where one is unknown, and none for any other reason"() {
        when:
        def misfit = new GivenBack.Misfit(reason, [new FieldName("details")], undeclared)

        then:
        misfit.reason() == reason
        misfit.undeclared() == undeclared
        misfit.path() == [new FieldName("details")]

        where:
        reason                          | undeclared
        DidNotFitReason.FIELD_UNKNOWN   | "Size " + Character.toString(0x0)
        DidNotFitReason.FIELD_UNKNOWN   | " size "
        DidNotFitReason.FIELD_UNKNOWN   | ""
        DidNotFitReason.NOTHING_GIVEN   | null
        DidNotFitReason.NOT_ITS_KIND    | null
    }
}
