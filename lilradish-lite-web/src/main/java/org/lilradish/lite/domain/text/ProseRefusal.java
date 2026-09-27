package org.lilradish.lite.domain.text;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.failure.RefusalCode;

/**
 * Why text sent to a model is refused, of the reasons a page names apart, spelt in lower case as the shared
 * cases table and the page spell them. Only text over lines is refused for a CRLF; on one line it is unusable.
 */
public enum ProseRefusal {
    CRLF,
    DIRECTION_CONTROL,
    INVISIBLE,
    TAG,
    UNUSABLE;

    /** The first reason, in the order a page looks as well, or none where {@code wellFormed} takes the text. */
    public static @Nullable ProseRefusal of(String said, boolean overLines, Consumer<String> wellFormed) {
        if (overLines && said.contains("\r\n")) {
            return CRLF;
        }
        ConcealingCharacter concealing = ConcealingCharacter.firstIn(said);
        if (concealing != null) {
            return of(concealing);
        }
        try {
            wellFormed.accept(said);
            return null;
        } catch (IllegalArgumentException refused) {
            return UNUSABLE;
        }
    }

    public static ProseRefusal of(ConcealingCharacter concealing) {
        return switch (concealing) {
            case DIRECTION_CONTROL -> DIRECTION_CONTROL;
            case TAG -> TAG;
        };
    }

    /** The code a page names this apart by; {@code unusable}, the value's own, where it names it no other way. */
    public RefusalCode code(RefusalCode unusable) {
        return switch (this) {
            case CRLF -> RefusalCode.PROSE_LINE_BREAK_CRLF;
            case DIRECTION_CONTROL -> RefusalCode.PROSE_DIRECTION_CONTROL;
            case INVISIBLE -> RefusalCode.PROSE_INVISIBLE_CHARACTER;
            case TAG -> RefusalCode.PROSE_TAG_CHARACTER;
            case UNUSABLE -> unusable;
        };
    }
}
