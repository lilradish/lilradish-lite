package org.lilradish.lite.domain.wire;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A JSON document as this system writes one, read into text by {@link CanonicalJson} alone. */
public sealed interface JsonValue {

    /**
     * Members stand in the order given, never a map's, since the written text is stored and compared
     * as it is. A name given twice is refused: two readers may keep different ones.
     */
    record JsonObject(List<JsonMember> members) implements JsonValue {

        public JsonObject {
            for (JsonMember member : members) {
                requireNonNull(member, "JsonObject holds a null member");
            }
            members = List.copyOf(members);
            Set<String> names = HashSet.newHashSet(members.size());
            for (JsonMember member : members) {
                if (!names.add(member.name())) {
                    throw new IllegalArgumentException("JsonObject names " + member.name() + " more than once");
                }
            }
        }
    }

    record JsonMember(String name, JsonValue value) {

        public JsonMember {
            requireNonNull(name, "JsonMember name must not be null");
            // By hand: the message names the member, and requireNonNull would build it on every call.
            if (value == null) {
                throw new NullPointerException("JsonMember " + name + " has no value; JsonNull stands for none");
            }
        }
    }

    record JsonArray(List<JsonValue> items) implements JsonValue {

        public JsonArray {
            for (JsonValue item : items) {
                requireNonNull(item, "JsonArray holds a null item; JsonNull stands for none");
            }
            items = List.copyOf(items);
        }
    }

    record JsonString(String value) implements JsonValue {

        public JsonString {
            requireNonNull(value, "JsonString must not be null; JsonNull stands for none");
        }
    }

    record JsonNumber(BigDecimal value) implements JsonValue {

        public JsonNumber {
            requireNonNull(value, "JsonNumber must not be null; JsonNull stands for none");
        }
    }

    record JsonBoolean(boolean value) implements JsonValue {}

    record JsonNull() implements JsonValue {}
}
