package org.lilradish.lite.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A model this deployment holds. Both limits count in the model's own units, which this system can
 * only estimate. Every refusal raised here names the model.
 *
 * @param modes those it offers beyond running as it is, in the order listed; a list because, bound
 *     as a set, a mode listed twice would be merged before it could be refused
 * @param sentPerCallLimit the most that may be sent to it in one call
 * @param charactersPerUnit how many characters this system counts as one of the model's units, to at
 *     most four decimal places
 * @param cameBackPerCallLimit the most it may give back in one call
 * @param pricesPerMillion in the order listed; none listed is none held
 */
public record DeployedModel(
        ModelName name,
        List<ModelMode> modes,
        long sentPerCallLimit,
        BigDecimal charactersPerUnit,
        long cameBackPerCallLimit,
        List<ModelPrice> pricesPerMillion) {

    private static final int CHARACTERS_PER_UNIT_SCALE = 4;

    private static final BigDecimal MOST_COUNTED = BigDecimal.valueOf(Long.MAX_VALUE);

    public DeployedModel {
        if (name == null) {
            throw new NullPointerException("DeployedModel name must not be null");
        }
        List<ModelMode> offered = modes == null ? List.of() : modes;
        Set<ModelMode> named = HashSet.newHashSet(offered.size());
        for (ModelMode mode : offered) {
            if (mode == null) {
                throw new NullPointerException("DeployedModel " + name.value() + " holds a null mode");
            }
            if (!named.add(mode)) {
                throw new IllegalArgumentException(
                        "DeployedModel " + name.value() + " offers " + mode.value() + " more than once");
            }
        }
        modes = List.copyOf(offered);
        if (sentPerCallLimit <= 0) {
            throw new IllegalArgumentException("DeployedModel " + name.value()
                    + " sent-per-call limit is missing or not positive: " + sentPerCallLimit);
        }
        if (charactersPerUnit == null) {
            throw new NullPointerException("DeployedModel " + name.value() + " has no characters-per-unit");
        }
        if (charactersPerUnit.signum() <= 0) {
            throw new IllegalArgumentException("DeployedModel " + name.value()
                    + " characters-per-unit is not positive: " + charactersPerUnit.toPlainString());
        }
        if (charactersPerUnit.stripTrailingZeros().scale() > CHARACTERS_PER_UNIT_SCALE) {
            throw new IllegalArgumentException("DeployedModel " + name.value() + " characters-per-unit has more than "
                    + CHARACTERS_PER_UNIT_SCALE + " decimal places: " + charactersPerUnit.toPlainString());
        }
        if (cameBackPerCallLimit <= 0) {
            throw new IllegalArgumentException("DeployedModel " + name.value()
                    + " came-back-per-call limit is missing or not positive: " + cameBackPerCallLimit);
        }
        List<ModelPrice> listed = pricesPerMillion == null ? List.of() : pricesPerMillion;
        Set<Currency> priced = HashSet.newHashSet(listed.size());
        for (ModelPrice price : listed) {
            if (price == null) {
                throw new NullPointerException("DeployedModel " + name.value() + " holds a null price");
            }
            if (!priced.add(price.currency())) {
                throw new IllegalArgumentException("DeployedModel " + name.value() + " prices "
                        + price.currency().getCurrencyCode() + " more than once");
            }
        }
        pricesPerMillion = List.copyOf(listed);
    }

    /**
     * Rounded up, and only ever from a whole count: parts shown apart are added as characters and
     * turned into units once, since rounded units do not add up to the rounded whole.
     */
    public long unitsOf(long characters) {
        return units(characters).longValueExact();
    }

    /**
     * The one judgement of what would be sent against the limit, nothing else comparing units to it: how many
     * units past it, none where it fits. A count held at {@link Long#MAX_VALUE} is past counting, and so is past
     * it by as much; units past what a long holds are held there too.
     */
    public long unitsPast(long characters) {
        if (characters == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        BigDecimal past = units(characters).subtract(BigDecimal.valueOf(sentPerCallLimit));
        return past.signum() <= 0 ? 0 : past.min(MOST_COUNTED).longValueExact();
    }

    /** Whether what would be sent fits, as {@link #unitsPast} judges it. */
    public boolean takes(long characters) {
        return unitsPast(characters) == 0;
    }

    private BigDecimal units(long characters) {
        if (characters < 0) {
            throw new IllegalArgumentException(
                    "DeployedModel " + name.value() + " cannot count a negative number of characters: " + characters);
        }
        return BigDecimal.valueOf(characters).divide(charactersPerUnit, 0, RoundingMode.CEILING);
    }
}
