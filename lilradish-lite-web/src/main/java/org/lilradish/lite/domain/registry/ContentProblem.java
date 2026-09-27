package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/**
 * One place in a version's stored content that does not hold, and what is wrong there; no words of its own.
 *
 * @param excess how far past its bound a size runs, only for a problem of size; none for every other
 */
public record ContentProblem(
        ContentProblemCode code,
        ContentPlace place,
        @Nullable Long excess) {

    public ContentProblem {
        requireNonNull(code, "ContentProblem code must not be null");
        requireNonNull(place, "ContentProblem place must not be null");
    }
}
