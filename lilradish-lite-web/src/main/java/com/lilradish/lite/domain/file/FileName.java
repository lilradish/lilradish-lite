package com.lilradish.lite.domain.file;

import static java.util.Objects.requireNonNull;

/** The original file name, stored exactly as given without trimming or normalising. */
public record FileName(String value) {

    public FileName {
        requireNonNull(value, "FileName value must not be null");
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 255) {
            throw new IllegalArgumentException("File name must hold one to 255 characters");
        }
        boolean visible = false;
        for (int offset = 0; offset < value.length(); ) {
            int character = value.codePointAt(offset);
            if (Character.isISOControl(character)
                    || character >= Character.MIN_SURROGATE && character <= Character.MAX_SURROGATE
                    || character == '\u2028'
                    || character == '\u2029') {
                throw new IllegalArgumentException("File name must be one line with no controls");
            }
            visible |= !Character.isWhitespace(character)
                    && !Character.isSpaceChar(character)
                    && !Character.isIdentifierIgnorable(character);
            offset += Character.charCount(character);
        }
        if (!visible) {
            throw new IllegalArgumentException("File name must have visible content");
        }
    }
}
