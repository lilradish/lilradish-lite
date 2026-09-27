package org.lilradish.lite.domain.workflow;

/**
 * Who produces a step's values, as the store tells them apart.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum StepProducer {
    MODEL("model"),
    PERSON("person"),
    CODE("code");

    private final String published;

    StepProducer(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
