package org.lilradish.lite.domain.text;

/**
 * The one rule every identifier is held to, whether a person writes it into what a group builds or a
 * deployment gives it to a model or a mode.
 */
public final class Identifiers {

    // DB-SPECIFIC: 63 characters, the longest PostgreSQL enum label in this alphabet; an identifier the store
    // keeps as text is checked there against the same shape, so one bound holds wherever it is kept.
    private static final int MAX_LENGTH = 63;

    private Identifiers() {}

    /**
     * Uppercase is refused, never folded, so a value reads back as it was written. No '.' or '-':
     * once the value sits inside a pointer or an address, either reads as a joiner.
     */
    public static String requireText(String value, String identifierType) {
        // By hand: the message names the caller's type, and requireNonNull would build it on every call.
        if (value == null) {
            throw new NullPointerException(identifierType + " must not be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(identifierType + " must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(identifierType + " must not exceed " + MAX_LENGTH + " characters");
        }
        if (!isLowercaseLetter(value.charAt(0))) {
            throw new IllegalArgumentException(identifierType + " must start with a lowercase letter");
        }
        for (int index = 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!isLowercaseLetter(character) && !isDigit(character) && character != '_') {
                throw new IllegalArgumentException(
                        identifierType + " must contain only lowercase letters, digits and _");
            }
        }
        return value;
    }

    /**
     * ASCII-only is deliberate: Unicode letters admit homoglyphs, so Character.isLowerCase would
     * reopen exactly the hole this closes.
     */
    private static boolean isLowercaseLetter(char character) {
        return character >= 'a' && character <= 'z';
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }
}
