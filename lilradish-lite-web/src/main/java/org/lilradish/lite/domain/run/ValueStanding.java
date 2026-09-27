package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

/**
 * Where one value of one try stands, judged within that try and never carried across tries: it stands where its
 * production needed no review or a review assured it, and nothing else makes it stand. The published spelling is
 * written out, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum ValueStanding {
    STANDS("stands"),
    WAITING_ON_REVIEW("waiting_on_review"),
    REFUSED("refused"),
    REFUSED_FOR_LENGTH("refused_for_length");

    private final String published;

    ValueStanding(String published) {
        this.published = published;
    }

    public String published() {
        return published;
    }

    /**
     * A refusal for length follows the value having stood, so it outranks what was decided on review. A review
     * that went wrong decides nothing and is the one review the try may have, so what it was to decide is refused.
     */
    public static ValueStanding of(TryRecord aTry, ValueRecord value) {
        requireNonNull(aTry, "ValueStanding try must not be null");
        requireNonNull(value, "ValueStanding value must not be null");
        boolean refusedForLength = aTry.reviews().stream()
                .filter(ReviewRecord::forLength)
                .anyMatch(review -> review.decisionOn(value.id())
                        .map(decision -> decision.outcome() == ReviewOutcome.REFUSED)
                        .orElse(false));
        if (refusedForLength) {
            return REFUSED_FOR_LENGTH;
        }
        if (!value.needsReview()) {
            return STANDS;
        }
        return aTry.onReview()
                .map(review -> review.lost() != null
                        ? REFUSED
                        : review.decisionOn(value.id())
                                .map(decision -> decision.outcome() == ReviewOutcome.ASSURED ? STANDS : REFUSED)
                                .orElse(WAITING_ON_REVIEW))
                .orElse(WAITING_ON_REVIEW);
    }
}
