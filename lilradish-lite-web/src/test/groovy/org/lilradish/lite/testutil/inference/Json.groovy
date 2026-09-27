package org.lilradish.lite.testutil.inference

import org.lilradish.lite.domain.wire.JsonValue

/**
 * A JSON document written as Groovy literals, so a spec's table reads as the answer it stands for: a map in
 * the order it was written, a list, text, a number read exactly, a boolean, and null for none.
 */
final class Json {

    private Json() {}

    static JsonValue of(Object literal) {
        switch (literal) {
            case null: return new JsonValue.JsonNull()
            case JsonValue: return literal as JsonValue
            case Map: return new JsonValue.JsonObject((literal as Map).collect { name, value ->
                new JsonValue.JsonMember(name as String, of(value))
            })
            case List: return new JsonValue.JsonArray((literal as List).collect { of(it) })
            case CharSequence: return new JsonValue.JsonString(literal.toString())
            case BigDecimal: return new JsonValue.JsonNumber(literal as BigDecimal)
            case Number: return new JsonValue.JsonNumber(new BigDecimal(literal.toString()))
            case Boolean: return new JsonValue.JsonBoolean(literal as boolean)
            default: throw new IllegalArgumentException("Json has no value for " + literal.getClass())
        }
    }
}
