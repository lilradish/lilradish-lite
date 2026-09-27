package org.lilradish.lite.app.inference;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Declaration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How anything the endpoint writes is read: a name given twice is refused rather than one of the two
 * taken, and a body longer than a completion carrying the longest answer the store keeps is not read
 * to its end, so a broken endpoint cannot fill the memory.
 */
final class EndpointJson {

    // The store keeps no text past the most one asking may send; a kept character is at longest two six-byte
    // escapes, and the rest is room for what a completion holds besides.
    private static final long LONGEST_READ = Declaration.MOST_SENT * 12 + 65_536;

    static final JsonMapper READER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(LONGEST_READ)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private EndpointJson() {}

    /** Absent where the member is missing or not a string, as the spec types every one this reads. */
    static @Nullable String stringIn(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
