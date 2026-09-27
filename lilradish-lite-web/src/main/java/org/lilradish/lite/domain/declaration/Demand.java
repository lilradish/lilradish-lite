package org.lilradish.lite.domain.declaration;

import org.jspecify.annotations.Nullable;

/**
 * What a field asks, which its host's {@link Demands} set by depth: every field says whether it must be given,
 * and a field that stands says what it takes to stand as well.
 */
public sealed interface Demand permits Demand.Given, Demand.Stands {

    /** Whether it may be left empty, a field holding many given no elements counting as empty. */
    boolean mustBe();

    record Given(boolean mustBe) implements Demand {}

    /**
     * @param standing none where not chosen yet
     * @param floor the whole percent a value stands at or above, only where it stands above one, and none there
     *     where not chosen yet
     */
    record Stands(
            boolean mustBe,
            @Nullable FieldStanding standing,
            @Nullable Integer floor) implements Demand {

        /** Level with the store's own range. */
        private static final int HIGHEST_FLOOR = 100;

        public Stands {
            if (floor != null && standing != FieldStanding.ABOVE_CONFIDENCE) {
                throw new IllegalArgumentException("Demand.Stands carries a floor only above a confidence");
            }
            if (floor != null && (floor < 1 || floor > HIGHEST_FLOOR)) {
                throw new IllegalArgumentException("Demand.Stands floor must be from 1 to 100: " + floor);
            }
        }
    }
}
