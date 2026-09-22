package org.lilradish.lite.domain.retrieval;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * One chunk as a splitting stage would produce it, before anything has embedded it.
 *
 * <p>The identity is assigned here rather than left to an insert, so that the citation reaching an
 * answer and the row in an index would be the same value rather than two to be reconciled.
 *
 * @param body wrapped once, where the split would happen; a stage holding a bare string would be the
 *     break in the chain that lets this text become an instruction to a later step
 */
public record Chunk(UUID chunkId, SourceId sourceId, int ordinal, UntrustedText body, int tokenCount) {

    public Chunk {
        requireNonNull(chunkId, "Chunk chunkId must not be null");
        requireNonNull(sourceId, "Chunk sourceId must not be null");
        requireNonNull(body, "Chunk body must not be null");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Chunk ordinal is a position, never negative: " + ordinal);
        }
        if (tokenCount < 0) {
            throw new IllegalArgumentException("Chunk tokenCount is a count, never negative: " + tokenCount);
        }
    }
}
