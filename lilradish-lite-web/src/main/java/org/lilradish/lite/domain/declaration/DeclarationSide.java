package org.lilradish.lite.domain.declaration;

/**
 * The two halves of a declaration, which are not one field wearing one name: what is taken may be
 * demanded and never assured, what is given back may be assured and never demanded.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling, which is also where each half of a version is addressed, is written out rather than folded
 * from the constant name, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum DeclarationSide {
    TAKES("takes"),
    GIVES("gives");

    private final String published;

    DeclarationSide(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }
}
