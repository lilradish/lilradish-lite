package org.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

/**
 * The four kinds counted separately on every call, so no pricing dimension can be missed. Long and
 * never double: a token figure is an exact integer.
 */
public record TokenTotals(long inputTokens, long outputTokens, long cachedTokens, long reasoningTokens) {

    /** The identity of {@link #plus}, and what a call that never reached a provider counts as. */
    public static final TokenTotals NONE = new TokenTotals(0, 0, 0, 0);

    public TokenTotals {
        requireNotNegative(inputTokens, "inputTokens");
        requireNotNegative(outputTokens, "outputTokens");
        requireNotNegative(cachedTokens, "cachedTokens");
        requireNotNegative(reasoningTokens, "reasoningTokens");
    }

    /**
     * Cached tokens are excluded: a provider prices them differently, so a budget is spent on the
     * other three while a cost is priced from all four at their own rates.
     */
    public long billableTokens() {
        return Math.addExact(Math.addExact(inputTokens, outputTokens), reasoningTokens);
    }

    public long totalTokens() {
        return Math.addExact(billableTokens(), cachedTokens);
    }

    /** Exact addition: a wrapped sum reads as negative, which the constructor would then blame on an input. */
    public TokenTotals plus(TokenTotals other) {
        requireNonNull(other, "TokenTotals to add must not be null");
        return new TokenTotals(
                Math.addExact(inputTokens, other.inputTokens),
                Math.addExact(outputTokens, other.outputTokens),
                Math.addExact(cachedTokens, other.cachedTokens),
                Math.addExact(reasoningTokens, other.reasoningTokens));
    }

    private static void requireNotNegative(long count, String field) {
        if (count < 0) {
            throw new IllegalArgumentException("TokenTotals " + field + " must not be negative: " + count);
        }
    }
}
