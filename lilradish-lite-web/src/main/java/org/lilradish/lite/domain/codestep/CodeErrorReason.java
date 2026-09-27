package org.lilradish.lite.domain.codestep;

/**
 * Why this system ended a try of code as gone wrong: what came back does not fit what the release declares it gives
 * back, the release no longer fits what the version binds at the try's start, running it failed on this side, or it
 * threw saying nothing readable. What the code itself said is never one of these, and is kept as it said it.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum CodeErrorReason {
    /** Nothing came back at all, not even an empty object. */
    GAVE_NOTHING("gave_nothing"),
    /** A member nothing declares, named as the code wrote it. */
    NOT_DECLARED("not_declared"),
    /** Nothing, or many holding none, for a field that must be given. */
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
    /** A field of what it takes is a term of a list this group does not hold. */
    TAKES_A_LIST_NOT_HERE("takes_a_list_not_here"),
    /** A field of what it gives back is a term of a list this group does not hold. */
    GIVES_A_LIST_NOT_HERE("gives_a_list_not_here"),
    /** What the version binds into it no longer matches what the release says it takes. */
    TAKES_OTHERWISE("takes_otherwise"),
    /** What a later step or the workflow reads out of it no longer matches what the release says it gives back. */
    GIVES_OTHERWISE("gives_otherwise"),
    /** Running it failed on this system's side, whatever the code did. */
    FAILED_ON_THIS_SIDE("failed_on_this_side"),
    /** The code threw, saying nothing readable about why. */
    SAID_NOTHING("said_nothing");

    private final String published;

    CodeErrorReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    /** Whether it is found in what the code gave back, which is then something, never nothing at all. */
    public boolean aboutWhatCameBack() {
        return switch (this) {
            case NOT_DECLARED,
                    NOTHING_GIVEN,
                    NOT_ITS_KIND,
                    TOO_LONG,
                    TOO_MANY,
                    NOT_A_TERM,
                    UNKEEPABLE,
                    TOO_LONG_TO_KEEP -> true;
            case GAVE_NOTHING,
                    TAKES_A_LIST_NOT_HERE,
                    GIVES_A_LIST_NOT_HERE,
                    TAKES_OTHERWISE,
                    GIVES_OTHERWISE,
                    FAILED_ON_THIS_SIDE,
                    SAID_NOTHING -> false;
        };
    }
}
