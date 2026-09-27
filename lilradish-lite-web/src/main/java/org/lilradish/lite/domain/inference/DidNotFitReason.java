package org.lilradish.lite.domain.inference;

/**
 * Why an answer, or what a code step gave back, does not fit the fields it was read against, as the store keeps it.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum DidNotFitReason {
    /** Could not be read, or is not the object the envelope asks for, holding what it names and nothing else. */
    NOT_THE_SHAPE("not_the_shape"),
    FIELD_MISSING("field_missing"),
    FIELD_UNKNOWN("field_unknown"),
    /** None, absent where a code step gives it, or many holding none, for a field that must be given. */
    NOTHING_GIVEN("nothing_given"),
    /** A value not written as its field's kind is, or many where one is declared, or one where many. */
    NOT_ITS_KIND("not_its_kind"),
    TOO_LONG("too_long"),
    TOO_MANY("too_many"),
    NOT_A_TERM("not_a_term"),
    /** A text holding a character the store cannot keep: a null character, or half a pair. */
    UNKEEPABLE("unkeepable"),
    /** A value that fits its field, but that the store would keep longer than it keeps any one value. */
    TOO_LONG_TO_KEEP("too_long_to_keep"),
    CONFIDENCE_MISSING("confidence_missing"),
    /** A confidence given for a field that asks none, or that nothing declares. */
    CONFIDENCE_UNASKED("confidence_unasked"),
    CONFIDENCE_NOT_A_PERCENT("confidence_not_a_percent"),
    UNDECIDED("undecided"),
    WORDS_MISSING("words_missing"),
    WORDS_TOO_LONG("words_too_long"),
    /** Words holding a character the store keeps out of words, a control above all. */
    WORDS_UNKEEPABLE("words_unkeepable"),
    /** An answer the model stopped giving at the most it may give back. */
    CUT_OFF("cut_off"),
    /** An answer holding something that could not be kept exactly as it came. */
    NOT_KEPT_AS_IT_CAME("not_kept_as_it_came");

    private final String published;

    DidNotFitReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
