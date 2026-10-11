package com.lilradish.lite.domain.file;

import static java.util.Objects.requireNonNull;

import java.util.Locale;

/** An accepted ending of a file's name, held in small letters. */
public record FileExtension(String value) {

    public FileExtension {
        requireNonNull(value, "FileExtension value must not be null");
        if (value.length() < 2 || value.length() > 33 || value.charAt(0) != '.') {
            throw new IllegalArgumentException("Unusable file extension");
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!alphanumeric(character)
                    && (character != '.' || index == value.length() - 1 || value.charAt(index - 1) == '.')) {
                throw new IllegalArgumentException("Unusable file extension");
            }
        }
        value = value.toLowerCase(Locale.ROOT);
    }

    /** Whether the original name ends with this extension, ignoring English letter case. */
    public boolean accepts(FileName name) {
        String original =
                requireNonNull(name, "FileExtension name must not be null").value();
        int start = original.length() - value.length();
        if (start < 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = original.charAt(start + index);
            if (character >= 'A' && character <= 'Z') {
                character = (char) (character + ('a' - 'A'));
            }
            if (character != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean alphanumeric(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9';
    }
}
