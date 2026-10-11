package com.lilradish.lite.domain.run;

/**
 * Why a step failed, where the failure is written down rather than worked out from its tries.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum RunStepFailureReason {
    UNCUTTABLE_LENGTH("uncuttable_length"),
    UNCLAIMED_VALUE("unclaimed_value"),
    MODEL_NOT_DEPLOYED("model_not_deployed"),
    TOO_DEEP("too_deep"),
    TOOL_NOT_OFFERED("tool_not_offered"),
    SOURCE_NO_LONGER_MATCHES("source_no_longer_matches"),
    SOURCE_LIST_NOT_HERE("source_list_not_here"),
    SEARCH_QUESTION_NO_LONGER_MATCHES("search_question_no_longer_matches");

    private final String published;

    RunStepFailureReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
