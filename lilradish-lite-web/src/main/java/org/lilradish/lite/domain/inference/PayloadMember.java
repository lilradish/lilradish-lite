package org.lilradish.lite.domain.inference;

import java.util.List;

/**
 * The members a {@link Payload} holds, each variant's in the order it writes them, which {@link SendMeasure}
 * counts off the same list: each switches over every member, so one added is added to both or neither compiles.
 */
enum PayloadMember {
    VERSION("envelope_version"),
    INSTRUCTION("instruction"),
    QUESTION("question"),
    TAKES("takes"),
    FOUND_ON_THE_WEB("found_on_the_web"),
    REFUSED("refused"),
    SEARCH_RECORD("search_record"),
    ANSWER("answer"),
    DECIDING("deciding");

    /** What {@link #REFUSED} holds first: the values as they were given. */
    static final String REFUSED_VALUES = "values";

    /** What {@link #REFUSED} holds second: the words each refused value was refused with. */
    static final String REFUSED_WORDS = "words";

    static final String SEARCHES = "searches";

    static final String SEARCHED_FOR = "searched_for";

    static final String FOUND = "found";

    static final String SEARCH_CUT = "cut";

    static final String SEARCH_NOT_AS_IT_CAME = "not_as_it_came";

    static final String SEARCH_LEFT_OUT = "left_out";

    private static final List<PayloadMember> TO_PRODUCE = List.of(VERSION, INSTRUCTION, TAKES);

    private static final List<PayloadMember> TO_PRODUCE_MARKED = List.of(VERSION, INSTRUCTION, TAKES, FOUND_ON_THE_WEB);

    private static final List<PayloadMember> TO_PRODUCE_TOLD = List.of(VERSION, INSTRUCTION, TAKES, REFUSED);

    private static final List<PayloadMember> TO_PRODUCE_MARKED_TOLD =
            List.of(VERSION, INSTRUCTION, TAKES, FOUND_ON_THE_WEB, REFUSED);

    private static final List<PayloadMember> TO_SEARCH = List.of(VERSION, QUESTION);

    private static final List<PayloadMember> TO_SEARCH_TOLD = List.of(VERSION, QUESTION, REFUSED);

    private static final List<PayloadMember> TO_REVIEW = List.of(VERSION, INSTRUCTION, TAKES, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_MARKED =
            List.of(VERSION, INSTRUCTION, TAKES, FOUND_ON_THE_WEB, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_TOLD =
            List.of(VERSION, INSTRUCTION, TAKES, REFUSED, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_MARKED_TOLD =
            List.of(VERSION, INSTRUCTION, TAKES, FOUND_ON_THE_WEB, REFUSED, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_SEARCH = List.of(VERSION, QUESTION, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_SEARCH_TOLD =
            List.of(VERSION, QUESTION, REFUSED, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_SEARCH_RECORDED =
            List.of(VERSION, QUESTION, SEARCH_RECORD, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_SEARCH_RECORDED_TOLD =
            List.of(VERSION, QUESTION, REFUSED, SEARCH_RECORD, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_UNINSTRUCTED = List.of(VERSION, TAKES, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_UNINSTRUCTED_MARKED =
            List.of(VERSION, TAKES, FOUND_ON_THE_WEB, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_UNINSTRUCTED_TOLD =
            List.of(VERSION, TAKES, REFUSED, ANSWER, DECIDING);

    private static final List<PayloadMember> TO_REVIEW_UNINSTRUCTED_MARKED_TOLD =
            List.of(VERSION, TAKES, FOUND_ON_THE_WEB, REFUSED, ANSWER, DECIDING);

    private final String written;

    PayloadMember(String written) {
        this.written = written;
    }

    String written() {
        return written;
    }

    static List<PayloadMember> sent(boolean told, boolean reviewing, boolean marked) {
        if (reviewing) {
            return marked ? (told ? TO_REVIEW_MARKED_TOLD : TO_REVIEW_MARKED) : (told ? TO_REVIEW_TOLD : TO_REVIEW);
        }
        return marked ? (told ? TO_PRODUCE_MARKED_TOLD : TO_PRODUCE_MARKED) : (told ? TO_PRODUCE_TOLD : TO_PRODUCE);
    }

    static List<PayloadMember> sentSearch(boolean told) {
        return told ? TO_SEARCH_TOLD : TO_SEARCH;
    }

    static List<PayloadMember> sentReviewSearch(boolean told, boolean recorded) {
        return recorded
                ? (told ? TO_REVIEW_SEARCH_RECORDED_TOLD : TO_REVIEW_SEARCH_RECORDED)
                : (told ? TO_REVIEW_SEARCH_TOLD : TO_REVIEW_SEARCH);
    }

    /** What a review of a production no instruction asked for holds: no instruction, and nothing in its place. */
    static List<PayloadMember> sentUninstructed(boolean told, boolean marked) {
        return marked
                ? (told ? TO_REVIEW_UNINSTRUCTED_MARKED_TOLD : TO_REVIEW_UNINSTRUCTED_MARKED)
                : (told ? TO_REVIEW_UNINSTRUCTED_TOLD : TO_REVIEW_UNINSTRUCTED);
    }
}
