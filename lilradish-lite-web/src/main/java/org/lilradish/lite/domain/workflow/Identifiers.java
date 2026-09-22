package org.lilradish.lite.domain.workflow;

final class Identifiers {

    /** Self-imposed: a future schema must be aligned to this. */
    private static final int MAX_LENGTH = 255;

    /** ':' and '/' stay out so they remain unambiguous joiners for callers building compound keys. */
    private static final String SEPARATORS = ".-_";

    private Identifiers() {}

    static String requireText(String value, String identifierType) {
        if (value == null) {
            throw new NullPointerException(identifierType + " must not be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(identifierType + " must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(identifierType + " must not exceed " + MAX_LENGTH + " characters");
        }
        if (!isAlphanumeric(value.charAt(0)) || !isAlphanumeric(value.charAt(value.length() - 1))) {
            throw new IllegalArgumentException(identifierType + " must start and end with a letter or digit");
        }
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (!isAlphanumeric(character) && SEPARATORS.indexOf(character) < 0) {
                throw new IllegalArgumentException(
                        identifierType + " must contain only letters, digits and " + SEPARATORS);
            }
        }
        return value;
    }

    /**
     * ASCII-only is deliberate: Unicode letters admit homoglyphs, so Character.isLetterOrDigit
     * would reopen exactly the hole this closes.
     */
    private static boolean isAlphanumeric(char character) {
        return (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9');
    }
}
