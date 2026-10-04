package org.lilradish.lite.domain.file;

import static java.util.Objects.requireNonNull;

import java.util.Locale;

/** A bare media type name, without parameters or wildcards. */
public record MediaTypeName(String value) {

    public MediaTypeName {
        requireNonNull(value, "MediaTypeName value must not be null");
        int slash = value.indexOf('/');
        if (slash < 1
                || slash != value.lastIndexOf('/')
                || !restricted(value, 0, slash)
                || !restricted(value, slash + 1, value.length())) {
            throw new IllegalArgumentException("Unusable media type name");
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    /** Parse a complete sender Content-Type, including valid parameters. */
    public static MediaTypeName fromHeader(String header) {
        requireNonNull(header, "MediaTypeName header must not be null");
        int index = 0;
        while (index < header.length() && header.charAt(index) != ';') {
            index++;
        }
        int baseStart = space(header, 0);
        int baseEnd = index;
        while (baseEnd > baseStart && (header.charAt(baseEnd - 1) == ' ' || header.charAt(baseEnd - 1) == '\t')) {
            baseEnd--;
        }
        String base = header.substring(baseStart, baseEnd);
        MediaTypeName type = new MediaTypeName(base);
        while (index < header.length()) {
            index++;
            index = space(header, index);
            int nameStart = index;
            while (index < header.length() && token(header.charAt(index))) {
                index++;
            }
            if (index == nameStart) {
                throw new IllegalArgumentException("Unusable media type parameter");
            }
            index = space(header, index);
            if (index >= header.length() || header.charAt(index++) != '=') {
                throw new IllegalArgumentException("Unusable media type parameter");
            }
            index = space(header, index);
            if (index < header.length() && header.charAt(index) == '"') {
                index++;
                boolean closed = false;
                while (index < header.length()) {
                    char character = header.charAt(index++);
                    if (character == '"') {
                        closed = true;
                        break;
                    }
                    if (character == '\\') {
                        if (index >= header.length() || !quotedPair(header.charAt(index++))) {
                            throw new IllegalArgumentException("Unusable media type parameter");
                        }
                    } else if (!quotedText(character)) {
                        throw new IllegalArgumentException("Unusable media type parameter");
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("Unusable media type parameter");
                }
            } else {
                int valueStart = index;
                while (index < header.length() && token(header.charAt(index))) {
                    index++;
                }
                if (index == valueStart) {
                    throw new IllegalArgumentException("Unusable media type parameter");
                }
            }
            index = space(header, index);
            if (index < header.length() && header.charAt(index) != ';') {
                throw new IllegalArgumentException("Unusable media type parameter");
            }
        }
        return type;
    }

    private static int space(String header, int at) {
        while (at < header.length() && (header.charAt(at) == ' ' || header.charAt(at) == '\t')) {
            at++;
        }
        return at;
    }

    private static boolean token(char character) {
        return alphanumeric(character) || "!#$%&'*+-.^_`|~".indexOf(character) >= 0;
    }

    private static boolean quotedText(char character) {
        return character == '\t'
                || character == ' '
                || character == '!'
                || (character >= '#' && character <= '[')
                || (character >= ']' && character <= '~')
                || character >= 128 && character <= 255;
    }

    private static boolean quotedPair(char character) {
        return character == '\t'
                || character == ' '
                || character >= '!' && character <= '~'
                || character >= 128 && character <= 255;
    }

    private static boolean restricted(String value, int start, int end) {
        int length = end - start;
        if (length < 1 || length > 127 || !alphanumeric(value.charAt(start))) {
            return false;
        }
        for (int index = start + 1; index < end; index++) {
            char character = value.charAt(index);
            if (!alphanumeric(character) && "!#$&-^_.+".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean alphanumeric(char character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9');
    }
}
