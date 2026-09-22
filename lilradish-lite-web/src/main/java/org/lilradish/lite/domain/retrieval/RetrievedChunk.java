package org.lilradish.lite.domain.retrieval;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * A candidate that survived the search, on its way into a context.
 *
 * <p>The chunk, the source it came from, the generation that embedded it and the lane that returned
 * it are all here because this is what a prompt would be assembled from: the chain has to be
 * recordable at that moment rather than reconstructed afterwards from whatever still agrees.
 *
 * @param score what the lane scored it, and finite: a NaN makes every comparator built on it
 *     intransitive, which {@link java.util.List#sort} detects and throws on
 */
public record RetrievedChunk(
        UUID chunkId, SourceId sourceId, UUID generationId, UntrustedText text, int rank, double score, Lane lane) {

    public RetrievedChunk {
        requireNonNull(chunkId, "RetrievedChunk chunkId must not be null");
        requireNonNull(sourceId, "RetrievedChunk sourceId must not be null");
        requireNonNull(generationId, "RetrievedChunk generationId must not be null");
        requireNonNull(text, "RetrievedChunk text must not be null");
        requireNonNull(lane, "RetrievedChunk lane must not be null");
        if (rank < 1) {
            throw new IllegalArgumentException("RetrievedChunk rank starts at one: " + rank);
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("RetrievedChunk score must be finite: " + score);
        }
    }

    /**
     * Lexical retrieval answers for exact terms, identifiers and rare words, dense retrieval for
     * paraphrase and semantic proximity. {@link #BOTH} is recorded rather than collapsed onto one of
     * them, because which lane found a chunk is what tells an operator which half of a hybrid search
     * is earning its keep.
     */
    public enum Lane {
        LEXICAL,
        DENSE,
        BOTH
    }
}
