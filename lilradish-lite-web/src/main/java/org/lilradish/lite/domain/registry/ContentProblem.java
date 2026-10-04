package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import org.jspecify.annotations.Nullable;

/** One place in a version's stored content that does not hold, and what is wrong there; no words of its own. */
public record ContentProblem(
        ContentProblemCode code,
        ContentPlace place,
        @Nullable Long excess,
        @Nullable FileLimits fileLimits) {

    public ContentProblem {
        requireNonNull(code, "ContentProblem code must not be null");
        requireNonNull(place, "ContentProblem place must not be null");
        boolean needsFileLimits = code == ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT
                || code == ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT;
        if (needsFileLimits != (fileLimits != null)) {
            throw new IllegalArgumentException("ContentProblem file limits must accompany only a file limit problem");
        }
    }

    /** This deployment's three limits on files. */
    public record FileLimits(long maximumBytes, int maximumPerField, long maximumTotalBytes) {
        public FileLimits {
            if (maximumBytes < 1 || maximumPerField < 1 || maximumTotalBytes < maximumBytes) {
                throw new IllegalArgumentException("ContentProblem file limits must be positive");
            }
        }
    }
}
