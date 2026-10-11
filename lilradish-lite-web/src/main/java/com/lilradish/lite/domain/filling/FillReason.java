package com.lilradish.lite.domain.filling;

/**
 * Published spellings are explicit for the reason {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 * Text refusals use {@link com.lilradish.lite.domain.text.ProseRefusal}'s published vocabulary.
 */
public enum FillReason {
    /** It must be given, and is not: no value, or text of nothing that shows. */
    MISSING("missing"),
    MALFORMED("malformed"),
    TOO_LONG("too_long"),
    TOO_MANY("too_many"),
    /** It is none of the terms its field offers. */
    NOT_A_TERM("not_a_term"),
    LINE_BREAK("line_break"),
    CRLF("crlf"),
    DIRECTION_CONTROL("direction_control"),
    TAG("tag"),
    /** Text holding what prose refuses otherwise: a control but a tab or a line feed, a separator, half a pair. */
    UNUSABLE("unusable");

    private final String published;

    FillReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
