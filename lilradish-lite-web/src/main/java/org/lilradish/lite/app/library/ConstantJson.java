package org.lilradish.lite.app.library;

import org.lilradish.lite.app.ExactJson;
import org.lilradish.lite.domain.wire.CanonicalJson;
import org.lilradish.lite.domain.wire.JsonValue;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * A constant between the JSON text a page or the store holds it as and the value a workflow version writes, read
 * as {@link ExactJson} reads a value.
 */
public final class ConstantJson {

    /* The parser itself refuses a name given twice: read token by token, nothing reaches the tree reader's check. */
    private static final ObjectReader READER =
            JsonMapper.builder().build().reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);

    private ConstantJson() {}

    /**
     * One value's JSON text as a page sends it, so no number crosses as a double; not one document, or a member named
     * twice, is refused as an argument, and a number {@link ExactJson} refuses as {@link NotWrittenPlainly}.
     */
    public static JsonValue sent(String written) {
        try (JsonParser parser = READER.createParser(written)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException("A constant sent holds no JSON document");
            }
            JsonValue read = ExactJson.value(parser, first, (ignored, why) -> new NotWrittenPlainly(why));
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("A constant sent holds more than one JSON document");
            }
            return read;
        } catch (JacksonException | NumberFormatException unread) {
            throw new IllegalArgumentException("A constant sent is not one JSON document", unread);
        }
    }

    /** As the store holds it; a document it will not read is a store gone wrong. */
    public static JsonValue stored(String written) {
        try {
            return sent(written);
        } catch (IllegalArgumentException unread) {
            throw new IllegalStateException("A stored constant is not one this system writes", unread);
        }
    }

    /** As {@link CanonicalJson} writes it, which refuses a text holding an unpaired surrogate. */
    static String written(JsonValue constant) {
        return CanonicalJson.write(constant);
    }

    /** A number JSON reads, but written as no value's number is: with an exponent, or as a zero with a minus sign. */
    static final class NotWrittenPlainly extends IllegalArgumentException {

        NotWrittenPlainly(String why) {
            super("A constant sent writes " + why);
        }
    }
}
