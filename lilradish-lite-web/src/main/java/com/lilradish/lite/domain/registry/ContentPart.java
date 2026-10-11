package com.lilradish.lite.domain.registry;

/**
 * Which part of a version's content a problem is in, as a page draws that part.
 *
 * <p>The published spelling is written out rather than folded from the constant name, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum ContentPart {
    INSTRUCTION("instruction"),
    DRAWING("drawing"),
    TAKES("takes"),
    GIVES("gives"),
    STEPS("steps"),
    HELPER("helper"),
    TERMS("terms"),
    /** The whole asking, rather than an individual content part. */
    ASKING("asking");

    private final String published;

    ContentPart(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
