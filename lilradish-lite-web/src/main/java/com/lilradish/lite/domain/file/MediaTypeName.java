package com.lilradish.lite.domain.file;

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
