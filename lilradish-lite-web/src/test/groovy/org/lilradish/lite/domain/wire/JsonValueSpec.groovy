package org.lilradish.lite.domain.wire

import org.lilradish.lite.domain.wire.JsonValue.JsonArray
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean
import org.lilradish.lite.domain.wire.JsonValue.JsonMember
import org.lilradish.lite.domain.wire.JsonValue.JsonNull
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber
import org.lilradish.lite.domain.wire.JsonValue.JsonObject
import org.lilradish.lite.domain.wire.JsonValue.JsonString
import spock.lang.Specification

class JsonValueSpec extends Specification {

    private static final JsonMember ZETA = new JsonMember("zeta", new JsonString("z"))
    private static final JsonMember ALPHA = new JsonMember("alpha", new JsonNull())
    private static final JsonMember MID = new JsonMember("mid", new JsonBoolean(true))

    /** A member is not a value, so nothing can stand one where a value goes. */
    def "a document is built from exactly six kinds of value"() {
        expect:
        JsonValue.permittedSubclasses as Set ==
                [JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull] as Set

        and:
        !JsonValue.isAssignableFrom(JsonMember)
    }

    def "an object keeps its members in the order given, detached from the caller's list"() {
        given:
        def members = new ArrayList([ZETA, ALPHA, MID])

        when:
        def object = new JsonObject(members)
        members.add(new JsonMember("late", new JsonNull()))

        then:
        object.members() == [ZETA, ALPHA, MID]
    }

    def "an object hands back a list that cannot be widened"() {
        given:
        def object = new JsonObject([ZETA])

        when:
        object.members().add(MID)

        then:
        thrown(UnsupportedOperationException)
        object.members() == [ZETA]
    }

    def "an object refuses a null member and a name given twice, naming the name"() {
        when:
        new JsonObject(members)

        then:
        def error = thrown(expectedException)
        error.message == expectedMessage

        where:
        members                                                 || expectedException        | expectedMessage
        [ZETA, null]                                            || NullPointerException     | "JsonObject holds a null member"
        [ZETA, ALPHA, new JsonMember("zeta", new JsonNull())]   || IllegalArgumentException | "JsonObject names zeta more than once"
    }

    def "a member refuses a missing name or value, pointing at the value that stands for none"() {
        when:
        new JsonMember(name, value)

        then:
        def error = thrown(NullPointerException)
        error.message == expectedMessage

        where:
        name    | value               || expectedMessage
        null    | new JsonNull()      || "JsonMember name must not be null"
        "total" | null                || "JsonMember total has no value; JsonNull stands for none"
    }

    def "an array keeps its items in the order given, detached from the caller's list"() {
        given:
        def items = new ArrayList<JsonValue>([new JsonString("b"), new JsonString("a")])

        when:
        def array = new JsonArray(items)
        items.add(new JsonNull())

        then:
        array.items() == [new JsonString("b"), new JsonString("a")]
    }

    def "an array hands back a list that cannot be widened"() {
        given:
        def array = new JsonArray([new JsonString("a")])

        when:
        array.items().add(new JsonNull())

        then:
        thrown(UnsupportedOperationException)
        array.items() == [new JsonString("a")]
    }

    def "an array refuses a null item, pointing at the value that stands for none"() {
        when:
        new JsonArray([new JsonString("a"), null])

        then:
        def error = thrown(NullPointerException)
        error.message == "JsonArray holds a null item; JsonNull stands for none"
    }

    def "a string refuses to hold nothing, pointing at the value that stands for none"() {
        when:
        new JsonString(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "JsonString must not be null; JsonNull stands for none"
    }

    def "a number refuses to hold nothing, pointing at the value that stands for none"() {
        when:
        new JsonNumber(null)

        then:
        def error = thrown(NullPointerException)
        error.message == "JsonNumber must not be null; JsonNull stands for none"
    }
}
