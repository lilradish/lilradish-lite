package org.lilradish.lite.domain.declaration;

/**
 * What lets a value stand without review.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum FieldStanding {
    /** Nobody is asked. */
    ALWAYS("always"),
    /** Somebody is always asked. */
    NEVER("never"),
    /** Asked only where the producer was not sure enough, which a person never is. */
    ABOVE_CONFIDENCE("above_confidence");

    private final String published;

    FieldStanding(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
