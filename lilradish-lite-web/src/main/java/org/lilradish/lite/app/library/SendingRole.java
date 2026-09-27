package org.lilradish.lite.app.library;

/**
 * What a step asks a model to do, as a page names it when it reads what the step could send. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
enum SendingRole {
    PRODUCING("producing"),
    REVIEWING("reviewing");

    private final String published;

    SendingRole(String published) {
        this.published = published;
    }

    String published() {
        return published;
    }
}
