package org.lilradish.lite.domain.retrieval;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * The label a claim in an answer anchors to, and everything needed to follow it back.
 *
 * <p>The label is short because a model has to be able to reproduce it in prose, and it is the only
 * part of this a reader ever sees. Sixteen characters is where that stops being a description and
 * becomes a check: a label longer than a model will carry through an answer anchors nothing. The
 * identity beside it is what a lineage record would be written from, which is why the two travel
 * together rather than the label being resolved again later.
 */
public record Citation(String label, UUID chunkId, SourceId sourceId, UUID generationId) {

    private static final int MAX_LABEL_LENGTH = 16;

    public Citation {
        requireNonNull(label, "Citation label must not be null");
        requireNonNull(chunkId, "Citation chunkId must not be null");
        requireNonNull(sourceId, "Citation sourceId must not be null");
        requireNonNull(generationId, "Citation generationId must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("Citation label must not be blank");
        }
        // Reported by length rather than echoed: a label carrying a line break forges a log line.
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("Citation label must be at most " + MAX_LABEL_LENGTH
                    + " characters, and this one is " + label.length());
        }
    }
}
