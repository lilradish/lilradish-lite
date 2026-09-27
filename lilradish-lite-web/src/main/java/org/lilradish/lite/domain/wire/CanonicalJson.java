package org.lilradish.lite.domain.wire;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.wire.JsonValue.JsonArray;
import org.lilradish.lite.domain.wire.JsonValue.JsonBoolean;
import org.lilradish.lite.domain.wire.JsonValue.JsonMember;
import org.lilradish.lite.domain.wire.JsonValue.JsonNull;
import org.lilradish.lite.domain.wire.JsonValue.JsonNumber;
import org.lilradish.lite.domain.wire.JsonValue.JsonObject;
import org.lilradish.lite.domain.wire.JsonValue.JsonString;

/**
 * The one spelling this system writes a JSON document in, so the same document is the same text in
 * every release: nothing between tokens, and a number as its plain decimal.
 *
 * <p>A string is escaped as a browser's own JSON writer escapes it, so one character written is one
 * character stored, bar the escapes.
 */
public final class CanonicalJson {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private CanonicalJson() {}

    /** An unpaired surrogate is refused: encoded, it becomes a character nobody wrote. */
    public static String write(JsonValue value) {
        requireNonNull(value, "CanonicalJson value must not be null");
        StringBuilder text = new StringBuilder();
        write(value, text);
        return text.toString();
    }

    // DB-SPECIFIC: PostgreSQL's own text of a jsonb value (jsonb.c JsonbToCStringWorker, json.c escape_json_char).
    /**
     * How many characters the store's own text of the value runs to, which its bound on a stored document counts:
     * a space after every comma and colon, and each control character escaped as the store escapes it.
     */
    public static long storedLength(JsonValue value) {
        requireNonNull(value, "CanonicalJson value must not be null");
        return switch (value) {
            case JsonObject object -> {
                long length = 2 + Math.max(0, 2L * (object.members().size() - 1));
                for (JsonMember member : object.members()) {
                    length += storedLength(member.name()) + 2 + storedLength(member.value());
                }
                yield length;
            }
            case JsonArray array -> {
                long length = 2 + Math.max(0, 2L * (array.items().size() - 1));
                for (JsonValue item : array.items()) {
                    length += storedLength(item);
                }
                yield length;
            }
            case JsonString string -> storedLength(string.value());
            case JsonNumber number -> number.value().toPlainString().length();
            case JsonBoolean flag -> flag.value() ? 4 : 5;
            case JsonNull absent -> 4;
        };
    }

    private static long storedLength(String text) {
        long length = 2;
        for (int index = 0; index < text.length(); index = text.offsetByCodePoints(index, 1)) {
            int character = text.codePointAt(index);
            if (character == '"'
                    || character == '\\'
                    || character == '\b'
                    || character == '\f'
                    || character == '\n'
                    || character == '\r'
                    || character == '\t') {
                length += 2;
            } else if (character < ' ') {
                length += 6;
            } else {
                length++;
            }
        }
        return length;
    }

    /** Whether the character at {@code index} is half of a pair standing alone, which no text written here holds. */
    public static boolean halfAPairAt(String text, int index) {
        char character = text.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 == text.length() || !Character.isLowSurrogate(text.charAt(index + 1));
        }
        return Character.isLowSurrogate(character)
                && (index == 0 || !Character.isHighSurrogate(text.charAt(index - 1)));
    }

    private static void write(JsonValue value, StringBuilder text) {
        switch (value) {
            case JsonObject object -> {
                text.append('{');
                boolean first = true;
                for (JsonMember member : object.members()) {
                    if (!first) {
                        text.append(',');
                    }
                    first = false;
                    writeString(member.name(), text);
                    text.append(':');
                    write(member.value(), text);
                }
                text.append('}');
            }
            case JsonArray array -> {
                text.append('[');
                boolean first = true;
                for (JsonValue item : array.items()) {
                    if (!first) {
                        text.append(',');
                    }
                    first = false;
                    write(item, text);
                }
                text.append(']');
            }
            case JsonString string -> writeString(string.value(), text);
            case JsonNumber number -> text.append(number.value().toPlainString());
            case JsonBoolean flag -> text.append(flag.value() ? "true" : "false");
            case JsonNull absent -> text.append("null");
        }
    }

    private static void writeString(String value, StringBuilder text) {
        text.append('"');
        int length = value.length();
        int unescapedFrom = 0;
        for (int index = 0; index < length; index++) {
            char character = value.charAt(index);
            if (Character.isSurrogate(character)) {
                if (halfAPairAt(value, index)) {
                    throw new IllegalArgumentException("CanonicalJson refuses a text holding an unpaired surrogate");
                }
                continue;
            }
            if (character >= ' ' && character != '"' && character != '\\') {
                continue;
            }
            text.append(value, unescapedFrom, index);
            switch (character) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\b' -> text.append("\\b");
                case '\t' -> text.append("\\t");
                case '\n' -> text.append("\\n");
                case '\f' -> text.append("\\f");
                case '\r' -> text.append("\\r");
                default ->
                    text.append("\\u00").append(HEX_DIGITS[character >> 4]).append(HEX_DIGITS[character & 0xf]);
            }
            unescapedFrom = index + 1;
        }
        text.append(value, unescapedFrom, length);
        text.append('"');
    }
}
