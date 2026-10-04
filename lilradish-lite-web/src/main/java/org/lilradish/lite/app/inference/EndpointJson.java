package org.lilradish.lite.app.inference;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.Declaration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How endpoint JSON is read: a name given twice is refused, and a body beyond the bound for its wire
 * shape is not read to its end.
 */
public final class EndpointJson {

    // The store keeps no text past the most one asking may send; a kept character is at longest two six-byte
    // escapes, and the rest is room for what a completion holds besides.
    static final long LONGEST_READ = Declaration.MOST_SENT * 12 + 65_536;

    // A compatible completion may carry both content and refusal, each with its own kept-text bound.
    static final long LONGEST_COMPATIBLE_COMPLETION =
            Math.addExact(Math.multiplyExact(Math.multiplyExact(Declaration.MOST_SENT, 2L), 12L), 65_536L);

    public static final JsonMapper READER = reader(LONGEST_READ);

    static final JsonMapper COMPATIBLE_COMPLETION_READER = reader(LONGEST_COMPATIBLE_COMPLETION);

    private static JsonMapper reader(long longest) {
        return JsonMapper.builder(JsonFactory.builder()
                        .streamReadConstraints(StreamReadConstraints.builder()
                                .maxDocumentLength(longest)
                                .build())
                        .build())
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .build();
    }

    private EndpointJson() {}

    /** Absent where the member is missing or not a string, as the spec types every one this reads. */
    public static @Nullable String stringIn(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
