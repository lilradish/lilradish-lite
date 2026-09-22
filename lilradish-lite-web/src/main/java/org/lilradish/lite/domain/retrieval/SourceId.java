package org.lilradish.lite.domain.retrieval;

import static java.util.Objects.requireNonNull;

/**
 * What a source document is known by where it came from — a path, a URL, a ticket key. The format is
 * the origin's rather than this system's, so this is not a charset: no narrower rule admits every
 * shape an ingestion path may already produce, and a whitelist here would be a guess.
 *
 * <p>Non-blank, tightened by one thing: nothing a reader cannot see for what it is. Not a whitelist
 * — the argument above still holds — but the categories no format the argument defends would use.
 * This value is interpolated into messages about the document it names, and each refused category is
 * a way to forge one. A control character or a line separator adds a line of the caller's own. A
 * format character — a zero-width space, a soft hyphen, a bidirectional override — leaves two
 * unequal identifiers rendering identically, so a message naming both reads as naming one, and an
 * override reverses the order of everything printed after it. An unpaired surrogate encodes to the
 * same UTF-8 bytes as another does, so two sources become one the moment either is written down.
 *
 * <p>A baseline will have to carry both checks before the two agree.
 */
public record SourceId(String value) {

    public SourceId {
        requireNonNull(value, "SourceId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SourceId must not be blank");
        }
        requireVisibleAndWellFormed(value);
    }

    /**
     * Reported by position and code point rather than echoed, for the reason the value is checked.
     * The position is an index into the string rather than a count of characters, the two parting
     * company at the first one outside the basic plane.
     */
    private static void requireVisibleAndWellFormed(String value) {
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int category = Character.getType(codePoint);
            if (category == Character.CONTROL
                    || category == Character.FORMAT
                    || category == Character.LINE_SEPARATOR
                    || category == Character.PARAGRAPH_SEPARATOR
                    || category == Character.SURROGATE) {
                throw new IllegalArgumentException(String.format(
                        "SourceId must not contain a control, format, separator or surrogate character,"
                                + " but the one at index %d is U+%04X",
                        index, codePoint));
            }
            index += Character.charCount(codePoint);
        }
    }
}
