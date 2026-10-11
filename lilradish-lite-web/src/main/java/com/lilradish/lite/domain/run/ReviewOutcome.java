package com.lilradish.lite.domain.run;

/**
 * The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum ReviewOutcome {
    ASSURED("assured"),
    REFUSED("refused");

    private final String published;

    ReviewOutcome(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
