package org.lilradish.lite.domain.inference;

import java.util.List;

/**
 * The members a {@link Payload} holds, each variant's in the order it writes them, which {@link SendMeasure}
 * counts off the same list: each switches over every member, so one added is added to both or neither compiles.
 */
enum PayloadMember {
    VERSION("envelope_version"),
    INSTRUCTION("instruction"),
    TAKES("takes"),
    REFUSED("refused"),
    ANSWER("answer"),
    DECIDING("deciding");

    /** What {@link #REFUSED} holds first: the values as they were given. */
    static final String REFUSED_VALUES = "values";

    /** What {@link #REFUSED} holds second: the words each refused value was refused with. */
    static final String REFUSED_WORDS = "words";

    private static final List<PayloadMember> TO_PRODUCE = List.of(VERSION, INSTRUCTION, TAKES);

    private static final List<PayloadMember> TO_PRODUCE_TOLD = List.of(VERSION, INSTRUCTION, TAKES, REFUSED);

    private static final List<PayloadMember> TO_REVIEW = List.of(VERSION, INSTRUCTION, TAKES, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_TOLD =
            List.of(VERSION, INSTRUCTION, TAKES, REFUSED, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_UNINSTRUCTED = List.of(VERSION, TAKES, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_UNINSTRUCTED_TOLD =
            List.of(VERSION, TAKES, REFUSED, ANSWER, DECIDING);

    private final String written;

    PayloadMember(String written) {
        this.written = written;
    }

    String written() {
        return written;
    }

    static List<PayloadMember> sent(boolean told, boolean reviewing) {
        if (reviewing) {
            return told ? TO_REVIEW_TOLD : TO_REVIEW;
        }
        return told ? TO_PRODUCE_TOLD : TO_PRODUCE;
    }

    /** What a review of a production no instruction asked for holds: no instruction, and nothing in its place. */
    static List<PayloadMember> sentUninstructed(boolean told) {
        return told ? TO_REVIEW_UNINSTRUCTED_TOLD : TO_REVIEW_UNINSTRUCTED;
    }
}
