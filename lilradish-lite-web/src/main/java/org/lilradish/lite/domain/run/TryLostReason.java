package org.lilradish.lite.domain.run;

/**
 * How a try ended without giving anything back, which spends it all the same.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum TryLostReason {
    DID_NOT_FIT("did_not_fit"),
    NOTHING_CAME_BACK("nothing_came_back"),
    ERRORED("errored");

    private final String published;

    TryLostReason(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
