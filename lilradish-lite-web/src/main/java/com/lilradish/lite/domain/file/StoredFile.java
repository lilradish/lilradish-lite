package com.lilradish.lite.domain.file;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.JsonValue.JsonMember;
import com.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import com.lilradish.lite.domain.wire.JsonValue.JsonObject;
import com.lilradish.lite.domain.wire.JsonValue.JsonString;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record StoredFile(UUID id, FileName name, long size) {

    public StoredFile {
        requireNonNull(id, "StoredFile id must not be null");
        requireNonNull(name, "StoredFile name must not be null");
        if (size < 0) {
            throw new IllegalArgumentException("StoredFile size must not be negative");
        }
    }

    /** Read persisted metadata, requiring its exact canonical wire shape. */
    public static StoredFile fromKept(JsonValue value) {
        if (!(value instanceof JsonObject object) || object.members().size() != 3) {
            throw new IllegalArgumentException("Unusable stored file metadata");
        }
        Map<String, JsonValue> values = new HashMap<>();
        for (JsonMember member : object.members()) {
            if (values.putIfAbsent(member.name(), member.value()) != null) {
                throw new IllegalArgumentException("Unusable stored file metadata");
            }
        }
        if (!(values.get("id") instanceof JsonString idText)
                || !(values.get("name") instanceof JsonString nameText)
                || !(values.get("size") instanceof JsonNumber sizeNumber)) {
            throw new IllegalArgumentException("Unusable stored file metadata");
        }
        try {
            UUID id = UUID.fromString(idText.value());
            if (!id.toString().equals(idText.value())) {
                throw new IllegalArgumentException("Unusable stored file metadata");
            }
            return new StoredFile(
                    id, new FileName(nameText.value()), sizeNumber.value().longValueExact());
        } catch (ArithmeticException | IllegalArgumentException refused) {
            throw new IllegalArgumentException("Unusable stored file metadata", refused);
        }
    }

    public JsonValue kept() {
        return new JsonObject(List.of(
                new JsonMember("id", new JsonString(id.toString())),
                new JsonMember("name", new JsonString(name.value())),
                new JsonMember("size", new JsonNumber(BigDecimal.valueOf(size)))));
    }
}
