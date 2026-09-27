package org.lilradish.lite.domain.registry;

/**
 * What kind of thing an entry is. An entry's kind never changes; only its content is versioned.
 *
 * <p>The store declares the same vocabulary, spelt as these constants and in their order. The published
 * spelling, and the segment a group's entries of the kind are addressed under, are written out rather than
 * folded from the constant name, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum EntryKind {
    WORKFLOW("workflow", "workflows"),
    QUESTION("question", "questions"),
    REFERENCE_LIST("reference_list", "reference-lists");

    private final String published;

    private final String segment;

    EntryKind(String published, String segment) {
        this.published = published;
        this.segment = segment;
    }

    public String published() {
        return published;
    }

    /** Where a group's entries of this kind are addressed, and a reader's page of them is. */
    public String segment() {
        return segment;
    }
}
