package org.lilradish.lite.app;

import java.util.ArrayList;
import java.util.List;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;

/**
 * One JSON value read token by token, members in order and numbers exactly; a number's text is judged here or nowhere,
 * the domain seeing only its decimal, and one {@link FieldKind#numberUnwritten} names is refused as the reader asks.
 */
public final class ExactJson {

    private ExactJson() {}

    /** The value starting at {@code token}, which the parser stands on. */
    public static JsonValue value(JsonParser parser, JsonToken token, Refusal refusal) {
        return switch (token) {
            case START_OBJECT -> {
                List<JsonMember> members = new ArrayList<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_OBJECT; next = parser.nextToken()) {
                    String name = parser.currentName();
                    members.add(new JsonMember(name, value(parser, parser.nextToken(), refusal)));
                }
                yield new JsonObject(members);
            }
            case START_ARRAY -> {
                List<JsonValue> items = new ArrayList<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_ARRAY; next = parser.nextToken()) {
                    items.add(value(parser, next, refusal));
                }
                yield new JsonArray(items);
            }
            case VALUE_STRING -> new JsonString(parser.getString());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> number(parser, refusal);
            case VALUE_TRUE -> new JsonBoolean(true);
            case VALUE_FALSE -> new JsonBoolean(false);
            case VALUE_NULL -> new JsonNull();
            default -> throw new IllegalStateException("A JSON text parser gave " + token + " where a value stands");
        };
    }

    private static JsonValue number(JsonParser parser, Refusal refusal) {
        FieldKind.NumberUnwritten unwritten = FieldKind.numberUnwritten(parser.getString());
        if (unwritten != null) {
            throw refusal.refused(
                    parser,
                    switch (unwritten) {
                        case WITH_EXPONENT -> "a number with an exponent";
                        case ZERO_WITH_MINUS -> "a zero with a minus sign";
                    });
        }
        return new JsonNumber(parser.getDecimalValue());
    }

    /** How a reader refuses a number no value is written as, thrown where it is met. */
    @FunctionalInterface
    public interface Refusal {

        RuntimeException refused(JsonParser parser, String why);
    }
}
