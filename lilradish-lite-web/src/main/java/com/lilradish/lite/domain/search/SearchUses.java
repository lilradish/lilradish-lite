package com.lilradish.lite.domain.search;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.inference.ModelCallOutcome;
import org.jspecify.annotations.Nullable;

/**
 * How many searches one call is counted at: as its answer said, or else at the most it asked for. The most it
 * asked for is the one written down as it was sent, so a release changed since never moves what it counts.
 *
 * @param uses as said, even past the most asked for; otherwise that most
 * @param reported whether as the answer said
 */
public record SearchUses(long uses, boolean reported) {

    public SearchUses {
        if (uses < 0) {
            throw new IllegalArgumentException("SearchUses uses must not be negative: " + uses);
        }
    }

    /**
     * A call turned away was never taken up: it searched nothing, costs nothing, and has no count at all. One
     * taken up that nothing came back from, or that went wrong, counts at the most it asked for.
     *
     * @param said what the answer said of the tool the call asked for; none where it said nothing, and only where
     *     something came back
     * @return none exactly where the call was turned away
     */
    public static @Nullable SearchUses counted(ModelCallOutcome ended, @Nullable Long said, long asked) {
        requireNonNull(ended, "SearchUses ended must not be null");
        if (asked < 1) {
            throw new IllegalArgumentException("SearchUses asked must be at least one: " + asked);
        }
        if (said != null && ended != ModelCallOutcome.CAME_BACK) {
            throw new IllegalArgumentException("SearchUses takes what an answer said only where one came back");
        }
        if (ended == ModelCallOutcome.TURNED_AWAY) {
            return null;
        }
        return said == null ? new SearchUses(asked, false) : new SearchUses(said, true);
    }
}
