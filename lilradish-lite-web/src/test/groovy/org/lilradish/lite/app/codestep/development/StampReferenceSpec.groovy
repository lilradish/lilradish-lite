package org.lilradish.lite.app.codestep.development

import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.wire.CanonicalJson
import org.lilradish.lite.domain.wire.JsonValue
import spock.lang.Specification

class StampReferenceSpec extends Specification {

    StampReference stamping = new StampReference()

    /** Pure, so a run again for the same value stamps it alike; what it gives back stands as it comes. */
    def "takes one complaint that must be given, gives back one reference that stands as it comes, and may run again"() {
        when:
        def declared = stamping.declaration()

        then:
        declared.takes().fields()*.name()*.value() == ["complaint"]
        declared.takes().fields()[0].shape() == new FieldShape.Text(4000)
        declared.takes().fields()[0].demand() == new Demand.Given(true)
        declared.gives().fields()*.name()*.value() == ["reference"]
        declared.gives().fields()[0].shape() == new FieldShape.Text(12)
        declared.gives().fields()[0].demand() == new Demand.Stands(true, FieldStanding.ALWAYS, null)
        declared.mayRunAgain()
    }

    def "stamps a complaint with a reference drawn from its words alone, alike each time and apart from another's"() {
        when:
        def first = stamping.run(complaint("The kettle came broken."))
        def again = stamping.run(complaint("The kettle came broken."))
        def other = stamping.run(complaint("The kettle came late."))

        then:
        CanonicalJson.write(first) ==~ /\{"reference":"SUP-[0-9A-F]{8}"\}/
        again == first
        other != first
    }

    def "refuses to stamp where it is given no complaint in text"() {
        when:
        stamping.run(takes)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "stamp_reference takes a complaint in text, and was given none"

        where:
        takes << [
                new JsonValue.JsonObject([]),
                new JsonValue.JsonObject([new JsonValue.JsonMember("complaint", new JsonValue.JsonNumber(1G))]),
                new JsonValue.JsonObject([new JsonValue.JsonMember("remark", new JsonValue.JsonString("Broken."))])]
    }

    private static JsonValue.JsonObject complaint(String said) {
        new JsonValue.JsonObject([new JsonValue.JsonMember("complaint", new JsonValue.JsonString(said))])
    }
}
