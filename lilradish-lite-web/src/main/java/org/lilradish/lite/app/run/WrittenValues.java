package org.lilradish.lite.app.run;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.file.StoredFile;
import org.lilradish.lite.domain.filling.FillField;
import org.lilradish.lite.domain.filling.Filling;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.wire.JsonValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A value as it goes out to a reader, written as a person filling it sends it: each value as its text, none as
 * null, many as an array and fields as an object of them. What the store holds that is not of its field's shape
 * fails the read.
 */
final class WrittenValues {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private WrittenValues() {}

    /** One value of {@code field}, none as null. */
    static JsonNode of(FillField field, JsonValue value) {
        return node(field, Filling.wire(field, value));
    }

    /** Every field in declared order, whatever order the store keeps them in. */
    static ObjectNode of(List<FillField> fields, JsonValue.JsonObject values) {
        return object(fields, Filling.wire(fields, values));
    }

    // Labels and values must come from this one place.
    static List<FillField> takesOf(RunSnapshot snapshot) {
        return FillField.of(
                snapshot.workflow().takes().fields(), snapshot.workflow().lists());
    }

    /** The field at {@code names} among {@code fields}, each name one level further in; none where none is. */
    static @Nullable FillField at(List<FillField> fields, List<FieldName> names) {
        List<FillField> level = fields;
        FillField found = null;
        for (FieldName name : names) {
            found = level.stream()
                    .filter(field -> field.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                return null;
            }
            level = found.fields();
        }
        return found;
    }

    private static JsonNode node(FillField field, JsonValue value) {
        if (field.kind() == FieldKind.FILE) {
            return file(value);
        }
        if (field.kind() == FieldKind.FIELDS) {
            if (value instanceof JsonValue.JsonNull) {
                return NODES.nullNode();
            }
            if (value instanceof JsonValue.JsonArray many) {
                ArrayNode array = NODES.arrayNode();
                many.items().forEach(item -> array.add(object(field.fields(), (JsonValue.JsonObject) item)));
                return array;
            }
            return object(field.fields(), (JsonValue.JsonObject) value);
        }
        return switch (value) {
            case JsonValue.JsonNull ignored -> NODES.nullNode();
            case JsonValue.JsonString text -> NODES.stringNode(text.value());
            case JsonValue.JsonBoolean _, JsonValue.JsonNumber _ ->
                throw new IllegalStateException("Filling writes every value as text, never as a flag or a number");
            case JsonValue.JsonArray items -> {
                ArrayNode array = NODES.arrayNode();
                items.items().forEach(item -> array.add(node(field, item)));
                yield array;
            }
            case JsonValue.JsonObject ignored -> throw new IllegalStateException("A scalar filling holds an object");
        };
    }

    private static JsonNode file(JsonValue value) {
        if (value instanceof JsonValue.JsonNull) {
            return NODES.nullNode();
        }
        if (value instanceof JsonValue.JsonArray many) {
            ArrayNode array = NODES.arrayNode();
            many.items().forEach(item -> array.add(file(item)));
            return array;
        }
        StoredFile stored = StoredFile.fromKept(value);
        ObjectNode file = NODES.objectNode();
        file.put("id", stored.id().toString());
        file.put("name", stored.name().value());
        file.put("type", stored.type().value());
        file.put("size", stored.size());
        return file;
    }

    private static ObjectNode object(List<FillField> fields, JsonValue.JsonObject members) {
        ObjectNode object = NODES.objectNode();
        for (int index = 0; index < fields.size(); index++) {
            FillField field = fields.get(index);
            JsonValue.JsonMember member = members.members().get(index);
            object.set(member.name(), node(field, member.value()));
        }
        return object;
    }
}
