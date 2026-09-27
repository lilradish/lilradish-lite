package org.lilradish.lite.domain.text;

import org.jspecify.annotations.Nullable;

/**
 * The characters that show nothing yet change how text is read, by a person or a model, as
 * {@link CharacterStanding} judges them. A joiner and a non-joiner are not among them: some words are spelt with one.
 */
public enum ConcealingCharacter {
    /** {@link CharacterStanding#DIRECTION_CONTROL}. */
    DIRECTION_CONTROL,
    /** {@link CharacterStanding#TAG}, what is unassigned in the block included. */
    TAG;

    /** Which of these the code point is, or none where it is neither. */
    public static @Nullable ConcealingCharacter of(int codePoint) {
        return switch (CharacterStanding.of(codePoint)) {
            case DIRECTION_CONTROL -> DIRECTION_CONTROL;
            case TAG -> TAG;
            default -> null;
        };
    }

    /** The first of these the text holds, reading from its start, or none where it holds neither. */
    public static @Nullable ConcealingCharacter firstIn(String text) {
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            ConcealingCharacter found = of(codePoint);
            if (found != null) {
                return found;
            }
            index += Character.charCount(codePoint);
        }
        return null;
    }
}
