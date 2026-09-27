package org.lilradish.lite.domain.filling;

/**
 * Why one filled value does not fit its field. The published spelling is written out rather than folded from the
 * constant name, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives; why text is refused is
 * spelt as {@link org.lilradish.lite.domain.text.ProseRefusal} is wherever a page names it.
 */
public enum FillReason {
    /** It must be given, and is not: no value, or text of nothing that shows. */
    MISSING("missing"),
    /** It is not written as its kind is. */
    MALFORMED("malformed"),
    TOO_LONG("too_long"),
    TOO_MANY("too_many"),
    /** It is none of the terms its field offers. */
    NOT_A_TERM("not_a_term"),
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
