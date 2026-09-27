package org.lilradish.lite.app.inference;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.ExactJson;
import org.lilradish.lite.domain.wire.JsonValue;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.ObjectReader;

/**
 * A model's answer read as the one JSON document it must be, or none where it is not: anything around the
 * document, a code fence included, a member named twice, and a number {@link ExactJson} refuses are not.
 */
public final class AnswerReader {

    /* Read token by token, which the endpoint's refusal of a name given twice in a tree does not reach. */
    private static final ObjectReader READER =
            EndpointJson.READER.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);

    private AnswerReader() {}

    public static @Nullable JsonValue read(String answer) {
        requireNonNull(answer, "AnswerReader answer must not be null");
        try (JsonParser parser = READER.createParser(answer)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                return null;
            }
            JsonValue read = ExactJson.value(parser, first, AnswerReader::refused);
            return parser.nextToken() == null ? read : null;
        } catch (JacksonException unreadable) {
            return null;
        }
    }

    private static StreamReadException refused(JsonParser parser, String why) {
        return new StreamReadException(parser, "AnswerReader refuses " + why);
    }
}
